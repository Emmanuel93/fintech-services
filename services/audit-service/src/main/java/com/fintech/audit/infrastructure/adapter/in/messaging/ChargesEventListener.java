package com.fintech.audit.infrastructure.adapter.in.messaging;

import com.fintech.audit.application.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ChargesEventListener {

    private static final Logger log = LoggerFactory.getLogger(ChargesEventListener.class);
    private static final String SOURCE = "charges-service";

    private final AuditService auditService;

    public ChargesEventListener(AuditService auditService) {
        this.auditService = auditService;
    }

    @KafkaListener(topics = "charges.charge-applied",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onChargeApplied(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "chargeId", "id");
        String partyId     = EventPayloadParser.field(p, "obligorPartyId", "partyId");
        auditService.record("CHARGES_CHARGE_APPLIED", SOURCE, aggregateId, partyId, null, payload);
        log.debug("audited CHARGES_CHARGE_APPLIED aggregateId={}", aggregateId);
    }

    @KafkaListener(topics = "charges.charge-reversed",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onChargeReversed(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "chargeId", "id");
        String partyId     = EventPayloadParser.field(p, "obligorPartyId", "partyId");
        auditService.record("CHARGES_CHARGE_REVERSED", SOURCE, aggregateId, partyId, null, payload);
        log.info("audited CHARGES_CHARGE_REVERSED aggregateId={}", aggregateId);
    }
}
