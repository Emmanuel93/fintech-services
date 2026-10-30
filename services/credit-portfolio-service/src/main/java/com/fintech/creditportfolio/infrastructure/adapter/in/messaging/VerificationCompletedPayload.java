package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * El veredicto de KYC/AML que emite compliance (D16).
 *
 * <p>{@code prospectId} viaja siempre; {@code partyId} sólo cuando compliance ya lo conoce.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerificationCompletedPayload(
        UUID partyId,
        UUID prospectId,
        String identityStatus,
        String screeningStatus,
        Boolean allowsToProceed,
        String blockReason) {}
