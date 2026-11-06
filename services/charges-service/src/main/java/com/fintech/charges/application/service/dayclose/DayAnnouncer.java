package com.fintech.charges.application.service.dayclose;

import com.fintech.charges.application.port.out.AccrualDayRepository;
import com.fintech.charges.application.port.out.AccrualDayRepository.AccrualDay;
import com.fintech.charges.application.port.out.AccrualDayRepository.ControlTotals;
import com.fintech.charges.application.port.out.AccrualDayRepository.Tallies;
import com.fintech.charges.application.port.out.AccrualUnitStore;
import com.fintech.charges.application.port.out.ChargeEventPublisher;
import com.fintech.charges.application.port.out.ChargeEventPublisher.DayAccrued;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Avisa {@code charges.day-accrued}: el día cerró, con sus cifras de control y las cuentas que quedaron
 * fuera del lote. Lo usan el monitor al cerrar el día y el job para volver a avisar lo que cartera no
 * confirma.
 */
@Service
public class DayAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(DayAnnouncer.class);

    private final ChargeEventPublisher publisher;
    private final AccrualDayRepository days;
    private final AccrualUnitStore units;
    private final BusinessClock clock;

    public DayAnnouncer(ChargeEventPublisher publisher, AccrualDayRepository days, AccrualUnitStore units,
                        BusinessClock clock) {
        this.publisher = publisher;
        this.days      = days;
        this.units     = units;
        this.clock     = clock;
    }

    public void announce(AccrualDay dia) {
        Tallies t = dia.tallies();
        ControlTotals c = dia.totals();
        try {
            publisher.publishDayAccrued(new DayAccrued(dia.businessDate(), t.ordinarySchedules(), t.ordinaryErrors(),
                    t.moratoriumSchedules(), t.moratoriumErrors(), c.ordinaryCharges(), c.ordinaryAmount(),
                    c.moratoriumCharges(), c.moratoriumAmount(), elapsedMs(dia),
                    dia.completedAt() != null ? dia.completedAt() : clock.now(),
                    dia.plannedUnits() == null ? 0 : dia.plannedUnits(), units.heldAccounts(dia.businessDate())));
            days.announced(dia.businessDate());
        } catch (RuntimeException e) {
            // El día ya está cerrado aquí; el aviso lo reintenta el seguimiento.
            log.error("No salió el aviso del cierre de cargos del {}: {}", dia.businessDate(), e.getMessage());
        }
    }

    static long elapsedMs(AccrualDay dia) {
        return dia.startedAt() != null && dia.completedAt() != null
                ? Duration.between(dia.startedAt(), dia.completedAt()).toMillis() : 0;
    }
}
