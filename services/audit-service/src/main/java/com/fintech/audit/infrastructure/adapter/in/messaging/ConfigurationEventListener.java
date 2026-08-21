package com.fintech.audit.infrastructure.adapter.in.messaging;

import com.fintech.audit.application.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ConfigurationEventListener {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationEventListener.class);
    private static final String SOURCE = "configuration-service";

    private final AuditService auditService;

    public ConfigurationEventListener(AuditService auditService) {
        this.auditService = auditService;
    }

    @KafkaListener(topics = "configuration.configuration-updated",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onConfigurationUpdated(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "key", "paramKey", "id");
        auditService.record("CONFIGURATION_UPDATED", SOURCE, aggregateId, null, null, payload);
        log.info("audited CONFIGURATION_UPDATED key={}", aggregateId);
    }
}
