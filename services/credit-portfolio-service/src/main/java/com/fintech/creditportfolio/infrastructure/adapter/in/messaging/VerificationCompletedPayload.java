package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** El veredicto de KYC/AML que emite compliance (D16). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerificationCompletedPayload(
        UUID partyId,
        String identityStatus,
        String screeningStatus,
        Boolean allowsToProceed,
        String blockReason) {}
