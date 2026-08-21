package com.fintech.accounting.application.service;

import com.fintech.accounting.application.AccountingProperties;
import com.fintech.accounting.application.port.out.AccountingEventPublisher;
import com.fintech.accounting.application.port.out.AccountingPeriodRepository;
import com.fintech.accounting.application.port.out.JournalEntryRepository;
import com.fintech.accounting.application.port.out.VoucherRepository;
import com.fintech.accounting.domain.AccountingPeriod;
import com.fintech.accounting.domain.JournalEntry;
import com.fintech.accounting.domain.Voucher;
import com.fintech.accounting.domain.VoucherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * El único sitio por donde se asienta.
 *
 * <p>Los tres posteadores —saldos, provisión y comisiones— entran aquí, y aquí se resuelven las tres
 * cosas que antes cada uno resolvía por su cuenta o no resolvía:
 *
 * <ul>
 *   <li><b>La póliza.</b> Un hecho económico produce un encabezado con folio consecutivo por (tipo,
 *       sucursal, período) y N pares balanceados debajo. Antes producía pares sueltos que sólo el
 *       prefijo del {@code sourceEventId} relacionaba.</li>
 *   <li><b>El período sale del hecho, no del reloj.</b> Antes era {@code YearMonth.now()} al
 *       postear: el devengo del 31 procesado a las 00:03 del día 1 caía en el mes siguiente.</li>
 *   <li><b>El período cerrado se respeta.</b> {@code accounting_periods} existía desde el primer
 *       changeset y nadie la consultaba.</li>
 * </ul>
 *
 * <p><b>Un período cerrado no rechaza el asiento.</b> Lo manda al primer período abierto y lo marca
 * extemporáneo conservando el mes al que pertenecía. Rechazarlo obligaría a una bandeja de asientos
 * caídos y a alguien que la trabaje; sin eso, el hecho se pierde en un log — y perder el hecho es
 * peor que asentarlo un mes tarde con la etiqueta puesta.
 */
@Service
@Transactional
public class VoucherPostingService {

    private static final Logger log = LoggerFactory.getLogger(VoucherPostingService.class);

    private final VoucherRepository voucherRepository;
    private final JournalEntryRepository journalRepository;
    private final AccountingPeriodRepository periodRepository;
    private final AccountingEventPublisher eventPublisher;
    private final AccountingProperties properties;

    public VoucherPostingService(VoucherRepository voucherRepository,
                                  JournalEntryRepository journalRepository,
                                  AccountingPeriodRepository periodRepository,
                                  AccountingEventPublisher eventPublisher,
                                  AccountingProperties properties) {
        this.voucherRepository = voucherRepository;
        this.journalRepository = journalRepository;
        this.periodRepository  = periodRepository;
        this.eventPublisher    = eventPublisher;
        this.properties        = properties;
    }

    /**
     * Un par cargo/abono dentro de una póliza.
     *
     * <p>Se conserva el par en vez del renglón de un solo lado porque ya es lo que guarda
     * {@code journal_entries}, y porque un hecho de tres renglones —un pago que liquida capital e
     * intereses— se expresa igual de bien como dos pares balanceados: caja/intereses y caja/capital.
     * La consola presenta los renglones de un solo lado agregando por cuenta dentro de la póliza.
     */
    public record Pair(String debitAccount, String creditAccount, BigDecimal amount, String description) {}

    /**
     * Funde los pares que mueven <b>el mismo par de cuentas</b> en un solo renglón.
     *
     * <p>Un hecho puede tocar dos veces la misma pareja sin que sea un error: un pago liquida interés
     * devengado y moratorios, y los dos viven en {@code 1203}, así que salen como dos
     * {@code 1101 → 1203}. La póliza tiene <b>un renglón por cuenta</b> —es como se lee y como se
     * devuelve—, y la base lo exige: {@code uq_journal_entries_source} es única por
     * (hecho, cargo, abono).
     *
     * <p>Sin fundirlos, el segundo renglón viola la restricción, la transacción entera falla, Kafka
     * reintenta nueve veces y <b>descarta el mensaje</b>. El pago no se asienta y no queda rastro en
     * ninguna pantalla: de veinte pagos reales sólo llegaban cinco al mayor, justo los que no tenían
     * moratorios. El síntoma no era un error visible, era una cifra que faltaba.
     */
    private static List<Pair> fundir(List<Pair> pairs) {
        java.util.Map<String, Pair> porCuentas = new java.util.LinkedHashMap<>();
        for (Pair p : pairs) {
            porCuentas.merge(p.debitAccount() + "|" + p.creditAccount(), p,
                    (a, b) -> new Pair(a.debitAccount(), a.creditAccount(),
                            a.amount().add(b.amount()), a.description()));
        }
        return List.copyOf(porCuentas.values());
    }

    /** Todo lo que define una póliza salvo sus pares. */
    public record VoucherRequest(
            String sourceEventId,
            String triggerEvent,
            Instant occurredAt,
            UUID creditAccountId,
            UUID obligorPartyId,
            String orgUnitCode,
            String concept
    ) {}

    /**
     * Asienta una póliza. Idempotente por {@code sourceEventId}: el mismo hecho reprocesado devuelve
     * la póliza que ya existe en vez de crear una segunda con otro folio.
     *
     * @return la póliza, o {@code null} si no había nada que asentar (todos los pares en cero).
     */
    public Voucher post(VoucherRequest req, List<Pair> pairs) {
        List<Pair> real = fundir(pairs.stream()
                .filter(p -> p.amount() != null && p.amount().signum() > 0)
                .toList());
        if (real.isEmpty()) return null;

        var existing = voucherRepository.findBySourceEventId(req.sourceEventId());
        if (existing.isPresent()) {
            log.debug("Póliza ya asentada para sourceEventId={} — idempotente", req.sourceEventId());
            return existing.get();
        }

        Instant occurredAt = req.occurredAt() != null ? req.occurredAt() : Instant.now();
        String factPeriod  = periodOf(occurredAt);
        String postPeriod  = resolvePostingPeriod(factPeriod);

        BigDecimal total = real.stream().map(Pair::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        VoucherType type = VoucherType.of(req.triggerEvent());

        Voucher voucher = voucherRepository.save(Voucher.open(
                type, req.orgUnitCode(), postPeriod,
                voucherRepository.nextFolio(type, req.orgUnitCode(), postPeriod),
                occurredAt, req.concept(), req.sourceEventId(), req.triggerEvent(),
                req.creditAccountId(), req.obligorPartyId(), total, factPeriod));

        List<JournalEntry> entries = new ArrayList<>();
        short lineNo = 1;
        for (Pair p : real) {
            JournalEntry e = JournalEntry.post(
                    req.sourceEventId(), req.triggerEvent(), req.creditAccountId(), req.obligorPartyId(),
                    p.debitAccount(), p.creditAccount(), p.amount(), properties.getCurrency(),
                    p.description() != null ? p.description() : req.concept(),
                    voucher.getVoucherId(), req.orgUnitCode(), postPeriod, occurredAt, lineNo++);
            entries.add(journalRepository.save(e));
        }

        // El evento sigue siendo por asiento y no por póliza: hay consumidores del contrato actual y
        // cambiarlo aquí los rompería sin avisar. Añadir un `voucher-posted` es trabajo aparte.
        entries.forEach(eventPublisher::publishJournalEntryCreated);

        log.debug("Póliza {} {}-{} unidad={} período={} total={} ({} pares)",
                type, type.name().charAt(0), voucher.getFolio(), req.orgUnitCode(), postPeriod, total, real.size());
        return voucher;
    }

    /**
     * Adónde va el asiento.
     *
     * <p>Si el período del hecho no existe todavía, se abre: el primer movimiento de un mes es lo que
     * lo crea, y exigir que alguien lo dé de alta antes es la forma clásica de que la contabilidad
     * deje de escribir a medianoche del día 1.
     */
    private String resolvePostingPeriod(String factPeriod) {
        AccountingPeriod p = periodRepository.findById(factPeriod).orElse(null);
        if (p == null) return periodRepository.save(AccountingPeriod.open(factPeriod)).getPeriod();
        if (p.isOpen()) return p.getPeriod();

        String target = periodRepository.firstOpenFrom(factPeriod)
                .map(AccountingPeriod::getPeriod)
                .orElseGet(() -> {
                    // Todos cerrados hasta hoy: se abre el mes corriente. No hay adónde más mandarlo,
                    // y devolverlo al cerrado invalidaría los estados ya publicados de ese mes.
                    String now = periodOf(Instant.now());
                    return periodRepository.findById(now)
                            .orElseGet(() -> periodRepository.save(AccountingPeriod.open(now)))
                            .getPeriod();
                });
        log.info("Asiento extemporáneo: el hecho es de {} (cerrado) y se asienta en {}", factPeriod, target);
        return target;
    }

    /**
     * {@code YYYYMM} en la zona contable.
     *
     * <p>La zona importa: en UTC, un hecho de las 19:00 del 31 de agosto en Ciudad de México ya es
     * septiembre, y el cierre mensual dejaría de coincidir con el día natural que usa negocio.
     */
    private String periodOf(Instant instant) {
        return YearMonth.from(instant.atZone(properties.zoneId())).toString().replace("-", "");
    }
}
