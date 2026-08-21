package com.fintech.party.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Proyección del evento ProspectCreatedEvent publicado por origination-service.
 * Solo los campos necesarios para crear el Party como PROSPECT.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProspectCreatedPayload(
        UUID      prospectId,
        String    prospectType,        // INDIVIDUAL | BUSINESS
        String    firstName,
        String    lastName1,
        String    lastName2,
        String    curp,
        String    rfc,
        LocalDate dateOfBirth,
        String    eventId
) {}
