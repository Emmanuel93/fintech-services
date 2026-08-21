package com.fintech.risk.infrastructure.adapter.in.messaging;

import com.fintech.risk.application.service.RiskProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DelinquencyStatusUpdatedListener {

    private static final Logger log = LoggerFactory.getLogger(DelinquencyStatusUpdatedListener.class);

    private final RiskProfileService riskProfileService;

    public DelinquencyStatusUpdatedListener(RiskProfileService riskProfileService) {
        this.riskProfileService = riskProfileService;
    }

    @KafkaListener(topics = "credit-portfolio.delinquency-status-updated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "delinquencyStatusUpdatedListenerContainerFactory")
    public void onMessage(DelinquencyStatusUpdatedPayload payload) {
        log.debug("delinquency-status-updated received creditAccountId={} days={}",
                payload.creditAccountId(), payload.daysDelinquent());
        riskProfileService.onDelinquencyStatusUpdated(payload.creditAccountId(), payload.daysDelinquent());
    }
}
