package com.fintech.collections.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Shared shape for CollectionCaseCreated / CollectionCaseEscalated. */
record CollectionCaseEventPayload(
        UUID caseId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String currentBucket,
        String previousBucket,
        int daysDelinquent,
        BigDecimal totalDebt,
        String strategy,
        Instant occurredOn
) {}
