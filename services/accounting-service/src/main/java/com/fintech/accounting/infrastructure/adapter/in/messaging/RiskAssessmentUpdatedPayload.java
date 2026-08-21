package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Inbound {@code risk.assessment-updated} — provisión IFRS-9 (monto absoluto; T4 asienta el delta). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RiskAssessmentUpdatedPayload(
        UUID creditAccountId,
        UUID obligorPartyId,
        BigDecimal provisionAmount,
        Instant calculatedAt
) {}
