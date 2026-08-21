package com.fintech.origination.application;

import com.fintech.origination.domain.ProductType;

import java.util.UUID;

/**
 * Carries a scoring decision back into origination (Phase C). Correlated to the
 * active CreditApplication by (prospectId, productType) — the current scoring event
 * is prospect-scoped; once scoring echoes applicationId (Phase D) this can use it directly.
 */
public record ApplyScoringDecisionCommand(
        UUID applicationId,  // present from Phase D — direct correlation; null → correlate by (prospectId, productType)
        UUID prospectId,
        ProductType productType,
        String decision,     // AUTO_APPROVED | MANUAL_REVIEW | REJECTED
        String riskLevel,    // BAJO | MEDIO | ALTO
        Integer totalScore,
        UUID evaluationId,
        String correlationId
) {}
