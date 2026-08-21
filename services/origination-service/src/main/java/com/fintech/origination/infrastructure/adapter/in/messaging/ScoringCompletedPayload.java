package com.fintech.origination.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
        String  eventId
) {}
