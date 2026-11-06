package com.fintech.scoring.domain.event;

import com.fintech.scoring.domain.NoHistoryRequirement;
import com.fintech.scoring.domain.ScoreEvaluation;
import com.fintech.scoring.domain.ScoringPolicy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

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
        Instant evaluatedAt,
        // Lo que la política pide a quien no tiene historial en buró; vacío en cualquier otro caso.
        // Origination lo convierte en una petición de documentos para la mesa.
        List<Requirement> requirements
) {
    /** Un dato adicional que se pide: el código para las máquinas, la etiqueta para las personas. */
    public record Requirement(String code, String label) {}

    public ScoringCompletedEvent {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    public static ScoringCompletedEvent from(ScoreEvaluation evaluation, ScoringPolicy policy, UUID applicationId) {
        return from(evaluation, policy, applicationId, List.of());
    }

    public static ScoringCompletedEvent from(ScoreEvaluation evaluation, ScoringPolicy policy, UUID applicationId,
                                             List<NoHistoryRequirement> requirements) {
        return new ScoringCompletedEvent(
                UUID.randomUUID().toString(),
                BusinessClock.current().now(),
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
                evaluation.getEvaluatedAt(),
                requirements.stream().map(r -> new Requirement(r.name(), r.label())).toList()
        );
    }
}
