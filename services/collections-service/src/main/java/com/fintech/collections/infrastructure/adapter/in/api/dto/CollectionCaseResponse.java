package com.fintech.collections.infrastructure.adapter.in.api.dto;

import com.fintech.collections.domain.CollectionCase;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CollectionCaseResponse(
        UUID caseId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String productType,
        String status,
        String currentBucket,
        int daysDelinquent,
        BigDecimal totalDebt,
        String assignedAgentId,
        String externalAgencyId,
        String strategy,
        Instant openedAt,
        Instant closedAt
) {
    public static CollectionCaseResponse from(CollectionCase c) {
        return new CollectionCaseResponse(
                c.getCaseId(), c.getCreditAccountId(), c.getObligorPartyId(), c.getProductType(),
                c.getStatus().name(), c.getCurrentBucket().name(), c.getDaysDelinquent(), c.getTotalDebt(),
                c.getAssignedAgentId(), c.getExternalAgencyId(), c.getStrategy(), c.getOpenedAt(), c.getClosedAt());
    }
}
