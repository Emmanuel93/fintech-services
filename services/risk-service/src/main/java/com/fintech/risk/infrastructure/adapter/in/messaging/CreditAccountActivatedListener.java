package com.fintech.risk.infrastructure.adapter.in.messaging;

import com.fintech.risk.application.service.RiskProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CreditAccountActivatedListener {

    private static final Logger log = LoggerFactory.getLogger(CreditAccountActivatedListener.class);

    private final RiskProfileService riskProfileService;

    public CreditAccountActivatedListener(RiskProfileService riskProfileService) {
        this.riskProfileService = riskProfileService;
    }

    @KafkaListener(topics = "credit-portfolio.credit-account-activated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditAccountActivatedListenerContainerFactory")
    public void onMessage(CreditAccountActivatedPayload payload) {
        log.debug("credit-account-activated received creditAccountId={} productType={}",
                payload.creditAccountId(), payload.productType());
        riskProfileService.onCreditAccountActivated(
                payload.creditAccountId(), payload.obligorPartyId(), payload.productType());
    }
}
