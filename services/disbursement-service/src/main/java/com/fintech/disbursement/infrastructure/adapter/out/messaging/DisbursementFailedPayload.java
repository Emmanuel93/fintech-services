package com.fintech.disbursement.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * El dinero no va a llegar. Quien lo consuma decide por {@code failureCode}, no por el texto:
 * {@code failureReason} es para humanos y puede cambiar.
 *
 * <p>{@code disbursementId} is null when the request never became an order (the instruction made it impossible, D67).
 * {@code retryable}: whether a new request may succeed (the failure code says so, {@code FailureCode#anotherAttemptMayWork});
 * {@code attempt}: which attempt of the caller's payment this was, from its metadata (1 when it does not number them).
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
        boolean retryable,
        int attempt,
        String correlationId,
        Instant occurredOn
) {}
