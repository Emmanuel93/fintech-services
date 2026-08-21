package com.fintech.risk.infrastructure.adapter.in.messaging;

import com.fintech.risk.application.service.RiskProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class BalanceUpdatedListener {

    private static final Logger log = LoggerFactory.getLogger(BalanceUpdatedListener.class);

    private final RiskProfileService riskProfileService;

    public BalanceUpdatedListener(RiskProfileService riskProfileService) {
        this.riskProfileService = riskProfileService;
    }

    @KafkaListener(topics = "credit-portfolio.balance-updated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "balanceUpdatedListenerContainerFactory")
    public void onMessage(BalanceUpdatedPayload payload) {
        log.debug("balance-updated received creditAccountId={} totalDebt={} status={}",
                payload.creditAccountId(), payload.totalDebt(), payload.accountStatus());
        riskProfileService.onBalanceUpdated(
                payload.creditAccountId(), payload.totalDebt(), payload.accountStatus());
    }
}
