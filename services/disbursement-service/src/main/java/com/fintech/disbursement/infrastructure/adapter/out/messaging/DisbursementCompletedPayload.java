package com.fintech.disbursement.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * El dinero llegó, con evidencia. Único evento que lo afirma.
 *
 * <p>Devuelve en eco {@code sourceSystem}, {@code sourceReference}, {@code sourceEventId} y
 * {@code sourceMetadata}: así el emisor correlaciona con lo suyo sin que este servicio tenga que
 * entender qué significan.
 */
public record DisbursementCompletedPayload(
        UUID disbursementId,
        UUID companyId,
        String sourceSystem,
        String sourceType,
        String sourceReference,
        String sourceEventId,
        Map<String, String> sourceMetadata,
        BigDecimal amount,
        String currency,
        String provider,
        String externalRef,
        String receiptUrl,
        boolean beneficiaryNameMatches,
        Instant settledAt,
        String correlationId,
        Instant occurredOn
) {}
