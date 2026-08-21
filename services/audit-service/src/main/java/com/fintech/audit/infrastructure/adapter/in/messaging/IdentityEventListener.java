package com.fintech.audit.infrastructure.adapter.in.messaging;

import com.fintech.audit.application.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Audita los intentos de inicio de sesión: el ACTOR de la seguridad. Sin esto, el log sabía qué le
 * pasó a cada agregado pero no quién entró (ni quién falló al entrar), que es justo lo que audita un
 * auditor. El actor es el username; el agregado, el partyId.
 */
@Component
public class IdentityEventListener {

    private static final Logger log = LoggerFactory.getLogger(IdentityEventListener.class);
    private static final String SOURCE = "identity-service";

    private final AuditService auditService;

    public IdentityEventListener(AuditService auditService) {
        this.auditService = auditService;
    }

    @KafkaListener(topics = "identity.login-attempted",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onLoginAttempted(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String username = EventPayloadParser.field(p, "username");
        String partyId  = EventPayloadParser.field(p, "partyId");
        String outcome  = EventPayloadParser.field(p, "outcome");
        String eventType = "IDENTITY_LOGIN_" + (outcome != null ? outcome : "ATTEMPTED");
        // actor = username: quién intentó entrar. El saneado del payload redacta cualquier secreto.
        auditService.record(eventType, SOURCE, partyId, partyId, null, username, payload);
        log.info("audited {} actor={} partyId={}", eventType, username, partyId);
    }
}
