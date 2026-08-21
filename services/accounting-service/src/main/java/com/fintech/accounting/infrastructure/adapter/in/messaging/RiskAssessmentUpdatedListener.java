package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fintech.accounting.application.service.ProvisionPostingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RiskAssessmentUpdatedListener {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentUpdatedListener.class);
    private final ProvisionPostingService provisionPostingService;

    public RiskAssessmentUpdatedListener(ProvisionPostingService provisionPostingService) {
        this.provisionPostingService = provisionPostingService;
    }

    @KafkaListener(topics = "risk.assessment-updated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "riskAssessmentUpdatedListenerContainerFactory")
    public void onMessage(RiskAssessmentUpdatedPayload p) {
        log.debug("risk.assessment-updated creditAccountId={} provision={}", p.creditAccountId(), p.provisionAmount());
        provisionPostingService.onRiskAssessment(p.creditAccountId(), p.obligorPartyId(), p.provisionAmount(), p.calculatedAt());
    }
}
