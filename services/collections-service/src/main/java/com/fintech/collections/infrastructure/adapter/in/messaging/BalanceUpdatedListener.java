package com.fintech.collections.infrastructure.adapter.in.messaging;

import com.fintech.collections.application.service.CaseManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class BalanceUpdatedListener {

    private static final Logger log = LoggerFactory.getLogger(BalanceUpdatedListener.class);

    private final CaseManagementService caseManagementService;

    public BalanceUpdatedListener(CaseManagementService caseManagementService) {
        this.caseManagementService = caseManagementService;
    }

    @KafkaListener(topics = "credit-portfolio.balance-updated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "balanceUpdatedListenerContainerFactory")
    public void onMessage(BalanceUpdatedPayload payload) {
        log.debug("balance-updated received creditAccountId={} status={} version={}",
                payload.creditAccountId(), payload.accountStatus(), payload.balanceVersion());

        caseManagementService.onBalanceUpdated(
                payload.creditAccountId(), payload.obligorPartyId(),
                payload.principalBalance(),
                payload.accruedInterestBalance() != null ? payload.accruedInterestBalance() : BigDecimal.ZERO,
                payload.penaltyBalance()         != null ? payload.penaltyBalance()         : BigDecimal.ZERO,
                payload.totalDebt()              != null ? payload.totalDebt()              : payload.principalBalance(),
                payload.balanceVersion());

        // CM-05: credit-portfolio has no dedicated ProductSettled event — derived here
        if ("SETTLED".equals(payload.accountStatus())) {
            caseManagementService.onProductSettled(payload.creditAccountId());
        }
    }
}
