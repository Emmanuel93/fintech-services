package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Forma de {@code disbursement.failed}. Se decide por {@code failureCode}, no por el texto:
 * {@code failureReason} es para humanos y puede cambiar. El {@code dispositionId} viene en el eco
 * de {@code sourceMetadata}/{@code sourceReference}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DisbursementFailedPayload(
        UUID disbursementId,
        String sourceSystem,
        String sourceType,
        String sourceReference,
        String sourceEventId,
        Map<String, String> sourceMetadata,
        String status,
        String failureCode,
        String failureReason,
        Instant occurredOn
) {}
