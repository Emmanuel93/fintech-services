package com.fintech.salesorg.infrastructure.adapter.in.api.dto;

import com.fintech.salesorg.domain.ClientLimits;
import com.fintech.salesorg.domain.PlacementPolicy;

import java.math.BigDecimal;

/**
 * A unit's own placement policy, per client type. A limit left out is inherited; both types left out clears the
 * unit's declaration.
 */
public record PlacementPolicyRequest(ClientLimitsRequest nuevo, ClientLimitsRequest revolvente) {

    public record ClientLimitsRequest(BigDecimal maxLoanAmount, BigDecimal maxOutstanding, Integer maxActiveLoans, Integer maxTerm) {

        ClientLimits toDomain() {
            return new ClientLimits(maxLoanAmount, maxOutstanding, maxActiveLoans, maxTerm);
        }
    }

    public PlacementPolicy toDomain() {
        return PlacementPolicy.of(nuevo == null ? null : nuevo.toDomain(), revolvente == null ? null : revolvente.toDomain());
    }
}
