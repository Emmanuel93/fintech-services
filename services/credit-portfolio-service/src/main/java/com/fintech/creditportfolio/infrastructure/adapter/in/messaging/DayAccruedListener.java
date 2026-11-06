package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fintech.creditportfolio.application.port.out.DayCloseRepository;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository.ChargesDay;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository.ChargesTotals;
import com.fintech.creditportfolio.infrastructure.job.AccountDayReleaser;
import com.fintech.creditportfolio.infrastructure.job.DayCloseRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Cargos devengó el día. Se anotan sus cifras de control y cartera avanza: cierra ese día —y los que
 * hubieran quedado esperando antes que él— si sus compuertas abren.
 */
@Component
class DayAccruedListener {

    private static final Logger log = LoggerFactory.getLogger(DayAccruedListener.class);

    private final DayCloseRepository dayCloses;
    private final DayCloseRunner runner;
    private final AccountDayReleaser releaser;

    DayAccruedListener(DayCloseRepository dayCloses, DayCloseRunner runner, AccountDayReleaser releaser) {
        this.dayCloses = dayCloses;
        this.runner    = runner;
        this.releaser  = releaser;
    }

    @KafkaListener(topics = "charges.day-accrued", containerFactory = "dayAccruedListenerContainerFactory")
    void onDayAccrued(DayAccruedPayload p) {
        if (p == null || p.businessDate() == null) return;
        List<UUID> fuera = p.heldAccounts() == null ? List.of() : p.heldAccounts();
        log.info("Cargos cerró el día {}: {} cargos ordinarios por {} y {} moratorios por {}; {} cuentas fuera del lote",
                p.businessDate(), p.ordinaryCharges(), p.ordinaryAmount(), p.moratoriumCharges(), p.moratoriumAmount(),
                fuera.size());
        dayCloses.recordChargesAccrued(new ChargesDay(p.businessDate(), new ChargesTotals(
                p.ordinaryCharges(), nz(p.ordinaryAmount()), p.moratoriumCharges(), nz(p.moratoriumAmount())), null, fuera));
        var hechos = runner.advance("charges.day-accrued");
        if (hechos.stream().noneMatch(r -> r.businessDate().equals(p.businessDate()))) {
            // Un día que ya estaba cerrado: cargos no vio la confirmación y volvió a avisar. Se le
            // contesta otra vez (y closing también la recibe, que es idempotente).
            runner.close(p.businessDate(), "charges.day-accrued");
        }
        // Las cuentas que devengaron tarde y esperaban a que cerrara su día.
        releaser.releasePending();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
