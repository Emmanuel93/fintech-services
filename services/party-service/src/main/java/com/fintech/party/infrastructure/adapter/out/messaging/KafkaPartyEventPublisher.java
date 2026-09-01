package com.fintech.party.infrastructure.adapter.out.messaging;

import com.fintech.party.application.port.out.PartyEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class KafkaPartyEventPublisher implements PartyEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaPartyEventPublisher.class);
    private static final String TOPIC_BLACKLISTED = "party.party-blacklisted";
    private static final String TOPIC_FISCAL_PROFILE = "party.fiscal-profile-updated";
    private static final String TOPIC_ROLE_GRANTED = "party.role-granted";
    private static final String TOPIC_ROLE_REVOKED = "party.role-revoked";
    private static final String TOPIC_EXECUTIVE_ASSIGNED = "party.executive-assigned";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaPartyEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishPartyBlacklisted(UUID partyId, String reason, String sourceList) {
        PartyBlacklistedPayload payload = new PartyBlacklistedPayload(
                partyId, reason, sourceList, Instant.now());
        kafkaTemplate.send(TOPIC_BLACKLISTED, partyId.toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish PartyBlacklisted partyId={}", partyId, ex);
                    } else {
                        log.debug("PartyBlacklisted published partyId={} offset={}",
                                partyId, result.getRecordMetadata().offset());
                    }
                });
    }

    @Override
    public void publishFiscalProfileUpdated(UUID partyId, UUID prospectId, String partyType, String rfc,
                                            String taxName, String taxRegime, String taxZipCode,
                                            String cfdiUse) {
        FiscalProfileUpdatedPayload payload = new FiscalProfileUpdatedPayload(
                partyId, prospectId, partyType, rfc, taxName, taxRegime, taxZipCode, cfdiUse, Instant.now());
        kafkaTemplate.send(TOPIC_FISCAL_PROFILE, partyId.toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish FiscalProfileUpdated partyId={}", partyId, ex);
                    } else {
                        log.debug("FiscalProfileUpdated published partyId={}", partyId);
                    }
                });
    }

    @Override
    public void publishRoleGranted(UUID partyId, String roleType, String grantedBy) {
        PartyRolePayload payload = new PartyRolePayload(partyId, roleType, grantedBy, Instant.now());
        kafkaTemplate.send(TOPIC_ROLE_GRANTED, partyId.toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish RoleGranted partyId={} role={}", partyId, roleType, ex);
                    } else {
                        log.debug("RoleGranted published partyId={} role={}", partyId, roleType);
                    }
                });
    }

    @Override
    public void publishRoleRevoked(UUID partyId, String roleType) {
        PartyRolePayload payload = new PartyRolePayload(partyId, roleType, null, Instant.now());
        kafkaTemplate.send(TOPIC_ROLE_REVOKED, partyId.toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish RoleRevoked partyId={} role={}", partyId, roleType, ex);
                    } else {
                        log.debug("RoleRevoked published partyId={} role={}", partyId, roleType);
                    }
                });
    }

    @Override
    public void publishExecutiveAssigned(UUID partyId, UUID executiveId, String executiveName, String clientName) {
        ExecutiveAssignedPayload payload = new ExecutiveAssignedPayload(
                partyId, executiveId, executiveName, clientName, Instant.now());

        // La clave es el ejecutivo y no el cliente: quien consuma esto va a agrupar por ejecutivo
        // —su cartera, su bandeja, su aviso— y así todo lo suyo cae en la misma partición y en orden.
        kafkaTemplate.send(TOPIC_EXECUTIVE_ASSIGNED, executiveId.toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish ExecutiveAssigned partyId={} executiveId={}",
                                partyId, executiveId, ex);
                    } else {
                        log.debug("ExecutiveAssigned published partyId={} executiveId={}", partyId, executiveId);
                    }
                });
    }

    /**
     * El hecho, con lo que hace falta para entenderlo sin volver a preguntar.
     *
     * <p>Lleva los nombres además de los ids a propósito: quien reaccione —un aviso, una bitácora—
     * necesita decir <em>quién</em> y <em>a quién</em>, y obligarle a consultar party para eso le
     * añadiría una dependencia síncrona a cambio de dos cadenas que aquí ya están en la mano.
     */
    record ExecutiveAssignedPayload(UUID partyId, UUID executiveId, String executiveName,
                                    String clientName, Instant occurredOn) {}
}
