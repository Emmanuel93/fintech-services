package com.fintech.audit.infrastructure.adapter.in.messaging;

import com.fintech.audit.application.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OriginationEventListener {

    private static final Logger log = LoggerFactory.getLogger(OriginationEventListener.class);
    private static final String SOURCE = "origination-service";

    private final AuditService auditService;

    public OriginationEventListener(AuditService auditService) {
        this.auditService = auditService;
    }

    @KafkaListener(topics = "origination.prospect-created",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onProspectCreated(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "prospectId", "id");
        auditService.record("ORIGINATION_PROSPECT_CREATED", SOURCE, aggregateId, null, null, payload);
        log.info("audited ORIGINATION_PROSPECT_CREATED aggregateId={}", aggregateId);
    }

    @KafkaListener(topics = "origination.score-requested",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onScoreRequested(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "applicationId", "prospectId");
        auditService.record("ORIGINATION_SCORE_REQUESTED", SOURCE, aggregateId, null, null, payload);
        log.info("audited ORIGINATION_SCORE_REQUESTED aggregateId={}", aggregateId);
    }

    @KafkaListener(topics = "origination.contract-signed",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onContractSigned(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "contractId", "applicationId");
        String partyId     = EventPayloadParser.field(p, "partyId", "obligorPartyId");
        auditService.record("ORIGINATION_CONTRACT_SIGNED", SOURCE, aggregateId, partyId, null, payload);
        log.info("audited ORIGINATION_CONTRACT_SIGNED aggregateId={}", aggregateId);
    }

    @KafkaListener(topics = "origination.application-rejected",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onApplicationRejected(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "applicationId", "id");
        String partyId     = EventPayloadParser.field(p, "partyId", "obligorPartyId");
        String actor       = EventPayloadParser.field(p, "decidedBy");
        auditService.record("ORIGINATION_APPLICATION_REJECTED", SOURCE, aggregateId, partyId, null, actor, payload);
        log.info("audited ORIGINATION_APPLICATION_REJECTED aggregateId={} actor={}", aggregateId, actor);
    }

    @KafkaListener(topics = "origination.application-approved",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onApplicationApproved(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "applicationId", "id");
        String partyId     = EventPayloadParser.field(p, "partyId", "obligorPartyId");
        String actor       = EventPayloadParser.field(p, "decidedBy");
        auditService.record("ORIGINATION_APPLICATION_APPROVED", SOURCE, aggregateId, partyId, null, actor, payload);
        log.info("audited ORIGINATION_APPLICATION_APPROVED aggregateId={} actor={}", aggregateId, actor);
    }

    @KafkaListener(topics = "origination.documents-requested",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onDocumentsRequested(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "applicationId", "id");
        String partyId     = EventPayloadParser.field(p, "prospectId", "partyId");
        String actor       = EventPayloadParser.field(p, "requestedBy");
        auditService.record("ORIGINATION_DOCUMENTS_REQUESTED", SOURCE, aggregateId, partyId, null, actor, payload);
        log.info("audited ORIGINATION_DOCUMENTS_REQUESTED aggregateId={} actor={}", aggregateId, actor);
    }
}
