package com.fintech.origination.application;

import com.fintech.origination.domain.ProductType;

import java.util.List;
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
        String correlationId,
        // Lo que la política pide a quien no tiene historial en buró (etiquetas); vacío si nada.
        List<String> requirements
) {
    public ApplyScoringDecisionCommand {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    public ApplyScoringDecisionCommand(UUID applicationId, UUID prospectId, ProductType productType,
                                       String decision, String riskLevel, Integer totalScore,
                                       UUID evaluationId, String correlationId) {
        this(applicationId, prospectId, productType, decision, riskLevel, totalScore, evaluationId,
                correlationId, List.of());
    }
}
