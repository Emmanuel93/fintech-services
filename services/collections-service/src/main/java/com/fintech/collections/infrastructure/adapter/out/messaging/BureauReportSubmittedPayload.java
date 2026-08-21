package com.fintech.collections.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record BureauReportSubmittedPayload(
        UUID reportId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String eventType,
        BigDecimal amountReported,
        String bureauReference,
        Instant occurredOn
) {}
