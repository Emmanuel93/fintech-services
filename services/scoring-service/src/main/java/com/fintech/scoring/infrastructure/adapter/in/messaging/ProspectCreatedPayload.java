package com.fintech.scoring.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.UUID;

/** Projection of ProspectCreatedEvent consumed by scoring-service. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProspectCreatedPayload(
        UUID   prospectId,
        // ── Clasificación — selección de modelo de scoring ───────────────────
        String prospectType,       // INDIVIDUAL | BUSINESS
        String channelType,        // MOBILE_APP | BRANCH | PARTNER
        String productTypeIntent,  // PERSONAL_LOAN | REVOLVING_LINE | PAYROLL_LOAN …
        // ── Identidad — requerida para consulta CDC ──────────────────────────
        String    firstName,
        String    lastName1,
        String    lastName2,
        String    curp,
        String    rfc,
        LocalDate dateOfBirth,
        // ── Domicilio — requerido para consulta CDC ───────────────────────────
        String street,
        String exteriorNumber,
        String interiorNumber,
        String neighborhood,
        String municipality,
        String city,
        String state,
        String postalCode,
        // ── Consentimiento ────────────────────────────────────────────────────
        boolean circuloConsentAccepted,
        String  eventId
) {}
