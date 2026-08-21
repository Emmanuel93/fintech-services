package com.fintech.risk.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Outbound {@code risk.assessment-updated} — consumed by Accounting (T4), Collections (opt), Audit. */
public record RiskAssessmentUpdatedPayload(
        UUID creditAccountId,
        UUID obligorPartyId,
        String productType,
        String ifrs9Stage,
        boolean stageChanged,
        String bucket,
        BigDecimal ead,
        BigDecimal expectedLossRate,
        BigDecimal provisionAmount,
        Instant calculatedAt
) {}
