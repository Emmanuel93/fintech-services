package com.fintech.audit.infrastructure.adapter.in.messaging;

import com.fintech.audit.application.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CreditProductEventListener {

    private static final Logger log = LoggerFactory.getLogger(CreditProductEventListener.class);
    private static final String SOURCE = "credit-product-service";

    private final AuditService auditService;

    public CreditProductEventListener(AuditService auditService) {
        this.auditService = auditService;
    }

    @KafkaListener(topics = "product-catalog.product-activated",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onProductActivated(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "productCode", "id");
        auditService.record("CREDIT_PRODUCT_ACTIVATED", SOURCE, aggregateId, null, null, payload);
        log.info("audited CREDIT_PRODUCT_ACTIVATED productCode={}", aggregateId);
    }

    @KafkaListener(topics = "product-catalog.product-retired",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onProductRetired(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "productCode", "id");
        auditService.record("CREDIT_PRODUCT_RETIRED", SOURCE, aggregateId, null, null, payload);
        log.info("audited CREDIT_PRODUCT_RETIRED productCode={}", aggregateId);
    }
}
