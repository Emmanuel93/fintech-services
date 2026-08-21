package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/** Inbound from collections-service (topic {@code collections.agreement-executed}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CollectionAgreementExecutedPayload(
        UUID agreementId,
        UUID caseId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String type,
        BigDecimal forgivenAmount,
        RestructureTermsPayload newTerms,
        String authorizedBy,
        String authorizationRef
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RestructureTermsPayload(BigDecimal newNominalRate, Integer newTermMonths, String newAmortizationType) {}
}
