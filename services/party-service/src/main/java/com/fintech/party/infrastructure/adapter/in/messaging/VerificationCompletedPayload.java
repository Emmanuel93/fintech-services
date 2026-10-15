package com.fintech.party.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** Lo que party necesita de {@code compliance.verification-completed}: el veredicto de la persona. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerificationCompletedPayload(
        UUID prospectId,
        UUID partyId,
        String identityStatus,
        String screeningStatus,
        Boolean allowsToProceed,
        String blockReason) {}
