package com.fintech.collections.infrastructure.adapter.in.messaging;

import com.fintech.collections.application.service.CaseManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DelinquencyStatusUpdatedListener {

    private static final Logger log = LoggerFactory.getLogger(DelinquencyStatusUpdatedListener.class);

    private final CaseManagementService caseManagementService;

    public DelinquencyStatusUpdatedListener(CaseManagementService caseManagementService) {
        this.caseManagementService = caseManagementService;
    }

    @KafkaListener(topics = "credit-portfolio.delinquency-status-updated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "delinquencyStatusUpdatedListenerContainerFactory")
    public void onMessage(DelinquencyStatusUpdatedPayload payload) {
        log.debug("delinquency-status-updated received creditAccountId={} days={}",
                payload.creditAccountId(), payload.daysDelinquent());
        caseManagementService.onDelinquencyStatusUpdated(
                payload.creditAccountId(), payload.obligorPartyId(), payload.daysDelinquent());
    }
}
