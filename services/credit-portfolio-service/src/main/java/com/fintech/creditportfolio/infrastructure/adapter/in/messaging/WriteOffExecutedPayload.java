package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** Inbound from collections-service (topic {@code collections.write-off-executed}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WriteOffExecutedPayload(
        String eventId,
        UUID creditAccountId
) {}
