package com.fintech.disbursement.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * El dinero no va a llegar. Quien lo consuma decide por {@code failureCode}, no por el texto:
 * {@code failureReason} es para humanos y puede cambiar.
 */
public record DisbursementFailedPayload(
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
        String status,
        String failureCode,
        String failureReason,
        String correlationId,
        Instant occurredOn
) {}
