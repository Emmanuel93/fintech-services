package com.fintech.risk.infrastructure.adapter.in.messaging;

import com.fintech.risk.application.service.RiskProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CollectionAgreementExecutedListener {

    private static final Logger log = LoggerFactory.getLogger(CollectionAgreementExecutedListener.class);

    private final RiskProfileService riskProfileService;

    public CollectionAgreementExecutedListener(RiskProfileService riskProfileService) {
        this.riskProfileService = riskProfileService;
    }

    @KafkaListener(topics = "collections.agreement-executed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "collectionAgreementExecutedListenerContainerFactory")
    public void onMessage(CollectionAgreementExecutedPayload payload) {
        log.debug("agreement-executed received creditAccountId={} type={}",
                payload.creditAccountId(), payload.type());
        // Only a RESTRUCTURE is forbearance; QUITA_PARCIAL's debt reduction flows in via BalanceUpdated.
        if ("RESTRUCTURE".equals(payload.type())) {
            riskProfileService.onRestructureExecuted(payload.creditAccountId());
        }
    }
}
