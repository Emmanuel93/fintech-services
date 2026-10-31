package com.fintech.origination.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

/** Projection of scoring-service's ScoringCompletedEvent (topic: scoring.scoring-completed). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScoringCompletedPayload(
        UUID    applicationId,       // present from Phase D (ScoreRequested-driven); null for manual re-eval
        UUID    prospectId,
        String  prospectType,
        String  productTypeIntent,   // maps to origination ProductType
        Integer totalScore,
        String  riskLevel,           // BAJO | MEDIO | ALTO
        String  decision,            // AUTO_APPROVED | MANUAL_REVIEW | REJECTED
        UUID    evaluationId,
        String  eventId,
        // Lo que la política del producto pide a quien no tiene historial en buró; vacío si nada.
        List<Requirement> requirements
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Requirement(String code, String label) {}

    /** Las etiquetas de lo que se pide, para la nota que lee la mesa (y la persona). */
    public List<String> requirementLabels() {
        return requirements == null ? List.of()
                : requirements.stream().map(r -> r.label() == null ? r.code() : r.label()).toList();
    }
}
