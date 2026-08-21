package com.fintech.audit.infrastructure.adapter.in.messaging;

import com.fintech.audit.application.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ScoringEventListener {

    private static final Logger log = LoggerFactory.getLogger(ScoringEventListener.class);
    private static final String SOURCE = "scoring-service";

    private final AuditService auditService;

    public ScoringEventListener(AuditService auditService) {
        this.auditService = auditService;
    }

    @KafkaListener(topics = "scoring.scoring-approved",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onScoringApproved(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "prospectId", "evaluationId", "id");
        auditService.record("SCORING_APPROVED", SOURCE, aggregateId, null, null, payload);
        log.info("audited SCORING_APPROVED aggregateId={}", aggregateId);
    }

    @KafkaListener(topics = "scoring.scoring-completed",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onScoringCompleted(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "prospectId", "applicationId");
        auditService.record("SCORING_COMPLETED", SOURCE, aggregateId, null, null, payload);
        log.info("audited SCORING_COMPLETED aggregateId={}", aggregateId);
    }
}
