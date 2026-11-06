package com.fintech.charges.application.service.dayclose;

import com.fintech.charges.application.port.out.AccrualDayRepository;
import com.fintech.charges.application.port.out.AccrualDayRepository.AccrualDay;
import com.fintech.charges.application.port.out.AccrualDayRepository.ControlTotals;
import com.fintech.charges.application.port.out.AccrualDayRepository.Tallies;
import com.fintech.charges.application.port.out.AccrualUnitStore;
import com.fintech.charges.application.port.out.AccrualUnitStore.Counts;
import com.fintech.charges.application.port.out.AccrualUnitStore.LateUnit;
import com.fintech.charges.application.port.out.AccrualUnitStore.Trouble;
import com.fintech.charges.application.port.out.ChargeEventPublisher;
import com.fintech.charges.application.port.out.ChargeEventPublisher.AccountAccrued;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lo que mantiene vivo el cierre repartido: recupera, reintenta, pone en cuarentena, suelta, reenvía y
 * cierra.
 *
 * <p>Por cada día abierto:
 * <ol>
 *   <li>devuelve a la cola lo que un pod muerto dejó tomado;</li>
 *   <li>reintenta las cuentas fallidas que aún tienen intentos; las que no, a cuarentena, con su error;</li>
 *   <li>reenvía las pendientes que nunca salieron o cuyo mensaje se perdió;</li>
 *   <li>y si ya no queda nada en vuelo, cierra el día y avisa a cartera con las cuentas que quedaron fuera.</li>
 * </ol>
 *
 * <p>Además suelta las cuentas retenidas cuyo día anterior ya cerró y avisa, una por una, las que
 * devengaron tarde ({@code charges.account-accrued}).
 *
 * <p>Puede correr a la vez en el job y en el monitor de otro pod: cada paso es condicional y el día lo
 * cierra —y lo avisa— una sola llamada.
 */
@Service
public class AccrualDayMonitor {

    private static final Logger log = LoggerFactory.getLogger(AccrualDayMonitor.class);
    private static final int LATE_PAGE = 500;

    private final AccrualDayRepository days;
    private final AccrualUnitStore units;
    private final AccrualDispatcher dispatcher;
    private final DayAnnouncer announcer;
    private final ChargeEventPublisher publisher;
    private final UnitSettings settings;
    private final AtomicInteger quarantinedGauge = new AtomicInteger();
    private final AtomicInteger heldGauge = new AtomicInteger();

    public AccrualDayMonitor(AccrualDayRepository days, AccrualUnitStore units, AccrualDispatcher dispatcher,
                             DayAnnouncer announcer, ChargeEventPublisher publisher, UnitSettings settings,
                             MeterRegistry meters) {
        this.days       = days;
        this.units      = units;
        this.dispatcher = dispatcher;
        this.announcer  = announcer;
        this.publisher  = publisher;
        this.settings   = settings;
        Gauge.builder("charges.day_close.accounts", quarantinedGauge, AtomicInteger::get)
                .description("Cuentas sin devengar en el cierre del día").tag("status", "quarantined").register(meters);
        Gauge.builder("charges.day_close.accounts", heldGauge, AtomicInteger::get)
                .description("Cuentas sin devengar en el cierre del día").tag("status", "held").register(meters);
    }

    /** Una vuelta sobre todo lo abierto. La corre el monitor programado. */
    public void tick() {
        int sueltas = units.releaseHeld();
        if (sueltas > 0) log.info("{} cuentas retenidas vuelven a la cola: su día anterior ya cerró", sueltas);

        var abiertos = new TreeSet<LocalDate>(days.running());
        abiertos.addAll(units.openDays());
        abiertos.forEach(this::drive);

        announceLate();
        var problemas = units.troubleCounts();
        quarantinedGauge.set(problemas.quarantined());
        heldGauge.set(problemas.held());
    }

    /**
     * Mueve un día: recupera, reintenta, pone en cuarentena, despacha y, si ya no queda nada en vuelo,
     * lo cierra.
     *
     * @return vacío si el día está cerrado; si no, por qué sigue abierto
     */
    public Optional<String> drive(LocalDate businessDate) {
        int recuperadas = units.reap(businessDate);
        if (recuperadas > 0) {
            log.warn("Cierre del {}: {} cuentas devueltas a la cola, su pod dejó vencer el arrendamiento",
                    businessDate, recuperadas);
        }
        units.requeueFailed(businessDate, settings.maxAttempts(), settings.retryBackoff());
        for (Trouble t : units.quarantineExhausted(businessDate, settings.maxAttempts())) {
            log.warn("Cuenta {} en cuarentena en el cierre del {} tras {} intentos: {}", t.creditAccountId(),
                    businessDate, t.attempts(), t.reason());
        }
        dispatcher.dispatch(businessDate);
        return tryComplete(businessDate);
    }

    private Optional<String> tryComplete(LocalDate businessDate) {
        AccrualDay dia = days.find(businessDate).orElse(null);
        if (dia == null || dia.done()) return Optional.empty();
        if (!dia.running()) return Optional.of("el día no está repartido (" + dia.status() + ")");

        Counts c = units.counts(businessDate);
        if (c.open() > 0) {
            return Optional.of(c.open() + " de " + c.total() + " cuentas en proceso");
        }
        int permitidas = settings.quarantinedAllowed(c.total());
        if (c.quarantined() > permitidas) {
            String motivo = c.quarantined() + " cuentas en cuarentena, más de las " + permitidas
                            + " que admite un día: es una falla general, no un dato; corrígela y reintenta la cuarentena";
            days.note(businessDate, motivo);
            log.error("Cierre de cargos del {} detenido: {}", businessDate, motivo);
            return Optional.of(motivo);
        }

        ControlTotals totales = days.totalsFor(businessDate);
        int fuera = c.quarantined() + c.held();
        Tallies cuentas = new Tallies(c.done(), fuera, totales.moratoriumCharges(), 0);
        if (days.complete(businessDate, cuentas, totales, fuera)) {
            AccrualDay hecho = days.find(businessDate).orElseThrow();
            announcer.announce(hecho);
            log.info("Cargos del día {} cerrados: {} cuentas · ordinario {} cargos por {} · moratorio {} cargos por {}"
                     + " · {} fuera del lote (cuarentena {}, retenidas {}) · {} ms",
                    businessDate, c.done(), totales.ordinaryCharges(), totales.ordinaryAmount(),
                    totales.moratoriumCharges(), totales.moratoriumAmount(), fuera, c.quarantined(), c.held(),
                    DayAnnouncer.elapsedMs(hecho));
        }
        return Optional.empty();
    }

    /** Avisa, cuenta por cuenta y en orden de día, las que devengaron después de avisado su día. */
    void announceLate() {
        for (LateUnit u : units.lateUnannounced(LATE_PAGE)) {
            try {
                var t = u.totals();
                publisher.publishAccountAccrued(new AccountAccrued(u.businessDate(), u.creditAccountId(),
                        t.ordinaryCharges(), t.ordinaryAmount(), t.moratoriumCharges(), t.moratoriumAmount()));
                units.lateAnnounced(u.businessDate(), u.creditAccountId());
                log.info("Cuenta {} devengó tarde su día {}: avisado a cartera", u.creditAccountId(), u.businessDate());
            } catch (RuntimeException e) {
                log.warn("No salió el aviso tardío de la cuenta {} del {}: {} — se reintenta",
                        u.creditAccountId(), u.businessDate(), e.getMessage());
                return;
            }
        }
    }
}
