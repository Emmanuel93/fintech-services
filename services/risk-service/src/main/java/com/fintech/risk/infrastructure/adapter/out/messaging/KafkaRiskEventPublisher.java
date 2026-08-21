package com.fintech.risk.infrastructure.adapter.out.messaging;

import com.fintech.risk.application.port.out.RiskEventPublisher;
import com.fintech.risk.domain.RiskProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class KafkaRiskEventPublisher implements RiskEventPublisher {

    static final String TOPIC_RISK_ASSESSMENT_UPDATED = "risk.assessment-updated";

    private static final Logger log = LoggerFactory.getLogger(KafkaRiskEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaRiskEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishRiskAssessmentUpdated(RiskProfile profile, boolean stageChanged) {
        var payload = new RiskAssessmentUpdatedPayload(
                profile.getCreditAccountId(), profile.getObligorPartyId(), profile.getProductType(),
                profile.getIfrs9Stage().name(), stageChanged, profile.getBucket().name(),
                profile.getEad(), profile.getExpectedLossRate(), profile.getProvisionAmount(),
                profile.getLastCalculatedAt() != null ? profile.getLastCalculatedAt() : Instant.now());
        kafkaTemplate.send(TOPIC_RISK_ASSESSMENT_UPDATED, profile.getCreditAccountId().toString(), payload);
        log.debug("published risk.assessment-updated creditAccountId={} stage={} provision={}",
                profile.getCreditAccountId(), profile.getIfrs9Stage(), profile.getProvisionAmount());
    }
}
