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
}
