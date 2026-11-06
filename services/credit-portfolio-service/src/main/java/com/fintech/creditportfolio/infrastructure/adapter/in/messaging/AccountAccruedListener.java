package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fintech.creditportfolio.application.port.out.DayCloseRepository.ChargesTotals;
import com.fintech.creditportfolio.infrastructure.job.AccountDayReleaser;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Una cuenta que había quedado fuera del cierre de su día devengó: se cuadra y se suelta a closing. */
@Component
class AccountAccruedListener {

    private final AccountDayReleaser releaser;

    AccountAccruedListener(AccountDayReleaser releaser) {
        this.releaser = releaser;
    }

    @KafkaListener(topics = "charges.account-accrued", containerFactory = "accountAccruedListenerContainerFactory")
    void onAccountAccrued(AccountAccruedPayload p) {
        if (p == null || p.businessDate() == null || p.creditAccountId() == null) return;
        releaser.receive(p.businessDate(), p.creditAccountId(), new ChargesTotals(
                p.ordinaryCharges(), nz(p.ordinaryAmount()), p.moratoriumCharges(), nz(p.moratoriumAmount())));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
