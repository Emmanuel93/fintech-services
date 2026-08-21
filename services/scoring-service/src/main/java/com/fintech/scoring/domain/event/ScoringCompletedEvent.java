package com.fintech.scoring.domain.event;

import com.fintech.scoring.domain.ScoreEvaluation;
import com.fintech.scoring.domain.ScoringPolicy;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitido por scoring-service al completar la evaluación crediticia.
 * Se publica para TODAS las decisiones: AUTO_APPROVED, MANUAL_REVIEW, REJECTED.
 * Topic: scoring.scoring-completed
 * Consumidores: origination (actualiza estado del prospecto), audit, notifications.
 */
public record ScoringCompletedEvent(
        String  eventId,
        Instant occurredOn,
        UUID    applicationId,   // null para re-evaluación manual; presente cuando viene de ScoreRequested
        UUID    evaluationId,
        UUID    prospectId,
        UUID    reportId,
        UUID    prefetchId,
        String  prospectType,
        String  productTypeIntent,
        int     totalScore,
        String  riskLevel,
        String  decision,       // AUTO_APPROVED | MANUAL_REVIEW | REJECTED
        Instant evaluatedAt
) {
    public static ScoringCompletedEvent from(ScoreEvaluation evaluation, ScoringPolicy policy, UUID applicationId) {
        return new ScoringCompletedEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                applicationId,
                evaluation.getEvaluationId(),
                evaluation.getProspectId(),
                evaluation.getReportId(),
                evaluation.getPrefetchId(),
                policy.getProspectType(),
                policy.getProductTypeIntent(),
                evaluation.getTotalScore(),
                evaluation.getRiskLevel().name(),
                evaluation.getDecision().name(),
                evaluation.getEvaluatedAt()
        );
    }
}
