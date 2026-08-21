package com.fintech.disbursement.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Salió, llegó, y el banco receptor lo devolvió. Es un hecho distinto de {@code failed}: hubo
 * movimiento de dinero en ambos sentidos y la contabilidad tiene que reflejar los dos.
 */
public record DisbursementReturnedPayload(
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
        String returnCauseCode,
        String correlationId,
        Instant occurredOn
) {}
