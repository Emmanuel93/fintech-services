package com.fintech.commission.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.commission.application.service.BeneficiaryProfileService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** {@code party.fiscal-profile-updated}: the RFC and regime that decide what is withheld from a beneficiary's lots. */
@Component
public class FiscalProfileUpdatedListener {

    private final BeneficiaryProfileService profiles;

    public FiscalProfileUpdatedListener(BeneficiaryProfileService profiles) {
        this.profiles = profiles;
    }

    @KafkaListener(topics = "${fintech.commission.topics.fiscal-profile-updated:party.fiscal-profile-updated}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "fiscalProfileUpdatedListenerContainerFactory")
    public void onMessage(FiscalProfileUpdatedPayload p) {
        if (p.partyId() == null) return;
        profiles.onFiscalProfileUpdated(p.partyId(), p.prospectId(), p.partyType(), p.rfc(), p.taxName(), p.taxRegime());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FiscalProfileUpdatedPayload(UUID partyId, UUID prospectId, String partyType, String rfc,
                                              String taxName, String taxRegime) {}
}
