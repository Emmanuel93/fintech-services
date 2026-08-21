package com.fintech.audit.infrastructure.adapter.in.messaging;

import com.fintech.audit.application.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);
    private static final String SOURCE = "payments";

    private final AuditService auditService;

    public PaymentEventListener(AuditService auditService) {
        this.auditService = auditService;
    }

    @KafkaListener(topics = "payments.payment-applied",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onPaymentApplied(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "paymentOrderId", "id");
        String partyId     = EventPayloadParser.field(p, "partyId", "obligorPartyId");
        auditService.record("PAYMENTS_PAYMENT_APPLIED", SOURCE, aggregateId, partyId, null, payload);
        log.debug("audited PAYMENTS_PAYMENT_APPLIED aggregateId={}", aggregateId);
    }

    @KafkaListener(topics = "payments.payment-returned",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onPaymentReturned(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "paymentOrderId", "id");
        auditService.record("PAYMENTS_PAYMENT_RETURNED", SOURCE, aggregateId, null, null, payload);
        log.info("audited PAYMENTS_PAYMENT_RETURNED aggregateId={}", aggregateId);
    }
}
