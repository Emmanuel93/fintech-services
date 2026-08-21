package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fintech.charges.application.service.AccrualScheduleService;
import com.fintech.charges.application.service.BalanceSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class BalanceUpdatedListener {

    private static final Logger log = LoggerFactory.getLogger(BalanceUpdatedListener.class);

    private final AccrualScheduleService scheduleService;
    private final BalanceSnapshotService snapshotService;

    public BalanceUpdatedListener(AccrualScheduleService scheduleService,
                                   BalanceSnapshotService snapshotService) {
        this.scheduleService = scheduleService;
        this.snapshotService = snapshotService;
    }

    @KafkaListener(
            topics = "credit-portfolio.balance-updated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "balanceUpdatedListenerContainerFactory")
    public void onBalanceUpdated(BalanceUpdatedPayload event) {
        log.debug("balance-updated received creditAccountId={} status={} version={}",
                event.creditAccountId(), event.accountStatus(), event.balanceVersion());

        scheduleService.updateBalanceFromEvent(
                event.creditAccountId(),
                event.principalBalance(),
                event.accountStatus());

        snapshotService.upsert(
                event.creditAccountId(),
                event.obligorPartyId(),
                event.principalBalance(),
                event.accruedInterestBalance() != null ? event.accruedInterestBalance() : BigDecimal.ZERO,
                event.penaltyBalance()         != null ? event.penaltyBalance()         : BigDecimal.ZERO,
                event.availableCredit(),
                event.totalDebt()              != null ? event.totalDebt()              : BigDecimal.ZERO,
                event.creditLimit(),
                event.balanceVersion(),
                event.accountStatus());
    }
}
