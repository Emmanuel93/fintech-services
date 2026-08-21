package com.fintech.scoring.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/** Projection of origination's ScoreRequestedEvent (topic: origination.score-requested). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScoreRequestedPayload(
        UUID       applicationId,
        UUID       prospectId,
        String     prospectType,    // INDIVIDUAL | BUSINESS
        String     productType,     // PERSONAL_LOAN | REVOLVING_LINE | …
        BigDecimal requestedAmount,
        Integer    requestedTerm,
        String     eventId
) {}
