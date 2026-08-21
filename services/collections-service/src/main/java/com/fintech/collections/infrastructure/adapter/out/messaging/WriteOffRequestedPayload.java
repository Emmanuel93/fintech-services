package com.fintech.collections.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record WriteOffRequestedPayload(
        UUID caseId,
        UUID creditAccountId,
        BigDecimal totalDebt,
        int daysDelinquent,
        String reason,
        String requestedBy,
        Instant occurredOn
) {}
