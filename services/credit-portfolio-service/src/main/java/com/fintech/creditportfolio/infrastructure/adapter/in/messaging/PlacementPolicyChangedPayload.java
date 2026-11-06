package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.creditportfolio.domain.beneficiary.ClientLimits;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** What the portfolio reads of {@code sales-org.placement-policy-changed}: one unit's resolved placement policy. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlacementPolicyChangedPayload(
        String eventId,
        UUID unitId,
        String unitCode,
        String path,
        String levelCode,
        UUID partyRef,
        String productType,
        boolean configured,
        String inheritedFrom,
        Limits nuevo,
        Limits revolvente,
        Instant occurredOn) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Limits(BigDecimal maxLoanAmount, BigDecimal maxOutstanding, Integer maxActiveLoans, Integer maxTerm) {

        public ClientLimits toDomain() {
            return new ClientLimits(maxLoanAmount, maxOutstanding, maxActiveLoans, maxTerm);
        }
    }
}
