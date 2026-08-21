package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Forma de {@code disbursement.completed}, recortada a lo que este dominio necesita. disbursement
 * devuelve en eco {@code sourceReference} y {@code sourceMetadata}: de ahí sale el {@code dispositionId}
 * que aquí correlaciona con la disposición en vuelo. El resto del vocabulario del conector se ignora.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DisbursementCompletedPayload(
        UUID disbursementId,
        String sourceSystem,
        String sourceType,
        String sourceReference,
        String sourceEventId,
        Map<String, String> sourceMetadata,
        BigDecimal amount,
        String currency,
        String provider,
        String externalRef,
        Instant settledAt,
        Instant occurredOn
) {}
