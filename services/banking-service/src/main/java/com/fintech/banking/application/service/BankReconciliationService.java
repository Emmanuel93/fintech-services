package com.fintech.banking.application.service;

import com.fintech.banking.application.port.out.BankReconciliationRepository;
import com.fintech.banking.application.port.out.InternalMovementLookupPort;
import com.fintech.banking.application.port.out.InternalMovementLookupPort.MovimientoInterno;
import com.fintech.banking.domain.BankMatch;
import com.fintech.banking.domain.BankStatementLine;
import com.fintech.banking.domain.SuspenseEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cruza lo que dice el banco contra lo que dice la plataforma, en <b>tres pasadas</b>.
 *
 * <ol>
 *   <li><b>Determinista</b> — coincide la clave de rastreo. Es única por orden: no hay margen.</li>
 *   <li><b>Heurística</b> — coincide importe y fecha, y <b>hay un solo candidato</b>. Se guarda con
 *       confianza para poder revisarla después.</li>
 *   <li><b>Puente</b> — nada cuadró. Sale como partida en conciliación, con motivo.</li>
 * </ol>
 *
 * <p><b>Con dos candidatos no se elige.</b> Podría cruzarse con «el primero» y quedaría cuadrado en
 * el reporte, pero cruzado contra el pago de otra persona: el saldo total daría bien y dos cuentas
 * individuales estarían mal. Ese es el peor desenlace posible aquí, porque nadie lo busca. Va a la
 * puente y una persona decide.
 */
@Service
public class BankReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(BankReconciliationService.class);

    /** Coincidieron importe y fecha, y sólo había un candidato. Alta, pero no certeza. */
    private static final BigDecimal CONFIANZA_IMPORTE_Y_FECHA = new BigDecimal("0.80");

    private final BankReconciliationRepository repo;
    private final InternalMovementLookupPort interno;

    public BankReconciliationService(BankReconciliationRepository repo,
                                     InternalMovementLookupPort interno) {
        this.repo    = repo;
        this.interno = interno;
    }

    /**
     * Ingiere un movimiento del banco. <b>Idempotente por {@code (cuenta, externalId)}</b>: los
     * bancos reenvían, y el poller repite el día.
     *
     * @return la línea, nueva o la que ya existía
     */
    @Transactional
    public BankStatementLine ingerir(UUID bankAccountId, LocalDate businessDate, String direction,
                                     BigDecimal amount, String externalId, String trackingKey,
                                     String reference, String counterparty,
                                     String counterpartyAccount) {
        Optional<BankStatementLine> yaEstaba = repo.findLineByExternalId(bankAccountId, externalId);
        if (yaEstaba.isPresent()) {
            log.debug("Movimiento {} ya ingerido en la cuenta {} — idempotente",
                    externalId, bankAccountId);
            return yaEstaba.get();
        }
        BankStatementLine linea = repo.saveLine(BankStatementLine.de(bankAccountId, businessDate,
                direction, amount, externalId, trackingKey, reference, counterparty,
                counterpartyAccount));
        log.info("Movimiento bancario ingerido cuenta={} externalId={} {} {}",
                bankAccountId, externalId, direction, amount);
        return linea;
    }

    /** Corre las tres pasadas sobre todo lo que quedó sin cruzar en la fecha. */
    @Transactional
    public Resultado conciliar(UUID bankAccountId, LocalDate businessDate) {
        List<BankStatementLine> lineas = repo.findLinesByDate(bankAccountId, businessDate);
        int deterministas = 0, heuristicos = 0, aPuente = 0;

        for (BankStatementLine linea : lineas) {
            if (!linea.estaSinCruzar()) continue;

            if (cruzarPorClave(linea))      { deterministas++; continue; }
            if (cruzarPorImporteYFecha(linea)) { heuristicos++; continue; }

            aPuente++;
            aLaPuente(linea, motivoDeNoCruce(linea));
        }

        log.info("Conciliación cuenta={} fecha={} líneas={} deterministas={} heurísticos={} puente={}",
                bankAccountId, businessDate, lineas.size(), deterministas, heuristicos, aPuente);
        return new Resultado(lineas.size(), deterministas, heuristicos, aPuente);
    }

    private boolean cruzarPorClave(BankStatementLine linea) {
        if (linea.getTrackingKey() == null || linea.getTrackingKey().isBlank()) {
            return false;
        }
        return interno.porClaveDeRastreo(linea.getTrackingKey())
                .filter(m -> m.amount().compareTo(linea.getAmount()) == 0)
                .map(m -> {
                    repo.saveMatch(BankMatch.determinista(linea.getId(), m.type(), m.reference(),
                            linea.getAmount()));
                    linea.marcarCruzado();
                    repo.saveLine(linea);
                    return true;
                })
                .orElseGet(() -> {
                    // Clave conocida con importe distinto: NO se cruza. Es la señal más nítida de
                    // que algo salió mal, y taparla cuadrando el reporte la haría invisible.
                    if (interno.porClaveDeRastreo(linea.getTrackingKey()).isPresent()) {
                        log.warn("Clave {} conocida pero con importe distinto: banco={} — no se cruza",
                                linea.getTrackingKey(), linea.getAmount());
                    }
                    return false;
                });
    }

    private boolean cruzarPorImporteYFecha(BankStatementLine linea) {
        List<MovimientoInterno> candidatos =
                interno.porImporteYFecha(linea.getAmount(), linea.getBusinessDate());

        if (candidatos.size() != 1) {
            if (candidatos.size() > 1) {
                // Elegir «el primero» cuadraría el reporte y cruzaría el pago contra otra persona:
                // el total daría bien y dos cuentas individuales estarían mal.
                log.warn("{} candidatos para el movimiento {} de {} — a la puente, decide una persona",
                        candidatos.size(), linea.getExternalId(), linea.getAmount());
            }
            return false;
        }

        MovimientoInterno m = candidatos.get(0);
        repo.saveMatch(BankMatch.heuristico(linea.getId(), m.type(), m.reference(),
                linea.getAmount(), CONFIANZA_IMPORTE_Y_FECHA));
        linea.marcarCruzado();
        repo.saveLine(linea);
        return true;
    }

    private void aLaPuente(BankStatementLine linea, String motivo) {
        repo.saveSuspense(SuspenseEntry.de(linea, motivo));
        linea.marcarEnPuente();
        repo.saveLine(linea);
    }

    /**
     * El motivo se guarda con la partida: «no cuadró» no le sirve a quien la tiene que resolver.
     *
     * <p>La <b>ambigüedad se declara primero</b>, tenga clave o no. Es el motivo más accionable de
     * todos —hay candidatos y sobran— y decir «sin movimiento equivalente» cuando había dos manda a
     * buscar en el lugar equivocado.
     */
    private String motivoDeNoCruce(BankStatementLine linea) {
        int candidatos = interno.porImporteYFecha(linea.getAmount(), linea.getBusinessDate()).size();
        if (candidatos > 1) {
            return "Ambiguo: " + candidatos + " movimientos internos con el mismo importe y fecha";
        }
        if (linea.getTrackingKey() != null && !linea.getTrackingKey().isBlank()) {
            return "Clave de rastreo " + linea.getTrackingKey()
                    + " sin movimiento interno que la respalde con ese importe";
        }
        return linea.esAbono()
                ? "Abono sin clave de rastreo ni movimiento interno equivalente"
                : "Cargo sin clave de rastreo: posible comisión o cargo del banco";
    }

    public record Resultado(int lineas, int deterministas, int heuristicos, int aPuente) {

        public int cruzados() { return deterministas + heuristicos; }
    }
}
