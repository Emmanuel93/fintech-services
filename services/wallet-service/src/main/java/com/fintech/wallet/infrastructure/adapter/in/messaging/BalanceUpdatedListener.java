package com.fintech.wallet.infrastructure.adapter.in.messaging;

import com.fintech.wallet.application.service.WalletProjectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class BalanceUpdatedListener {

    private static final Logger log = LoggerFactory.getLogger(BalanceUpdatedListener.class);

    private final WalletProjectionService projectionService;

    public BalanceUpdatedListener(WalletProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @KafkaListener(
            topics = "credit-portfolio.balance-updated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "balanceUpdatedListenerContainerFactory")
    public void onBalanceUpdated(BalanceUpdatedPayload event) {
        log.debug("balance-updated received creditAccountId={} status={} version={}",
                event.creditAccountId(), event.accountStatus(), event.balanceVersion());

        projectionService.onBalanceUpdated(
                event.creditAccountId(),
                event.obligorPartyId(),
                event.principalBalance(),
                event.accruedInterestBalance() != null ? event.accruedInterestBalance() : BigDecimal.ZERO,
                event.penaltyBalance()         != null ? event.penaltyBalance()         : BigDecimal.ZERO,
                event.availableCredit(),
                event.totalDebt()              != null ? event.totalDebt()              : event.principalBalance(),
                event.accountStatus(),
                event.balanceVersion());
    }
}
