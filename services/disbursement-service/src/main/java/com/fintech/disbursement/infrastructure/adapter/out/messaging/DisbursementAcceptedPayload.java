package com.fintech.disbursement.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * El proveedor tomó la orden. <strong>No dice que el dinero llegó</strong> — para eso está
 * {@code disbursement.completed}. Existe para operación y auditoría, no para cerrar ciclos de
 * negocio.
 */
public record DisbursementAcceptedPayload(
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
        String correlationId,
        Instant occurredOn
) {}
