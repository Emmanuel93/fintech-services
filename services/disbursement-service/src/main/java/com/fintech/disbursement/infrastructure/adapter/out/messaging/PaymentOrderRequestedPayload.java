package com.fintech.disbursement.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Orden de pago para un conector. Vocabulario de pagos, y nada más.
 *
 * <p>No dice "desembolso" ni menciona a STP: es el contrato mínimo que cualquier conector de pagos
 * puede consumir. {@code paymentRequestId} es el {@code disbursementId} — el conector lo usa como
 * clave de idempotencia y lo devuelve en su resultado.
 */
public record PaymentOrderRequestedPayload(
        UUID paymentRequestId,
        UUID companyId,
        BigDecimal amount,
        String currency,
        String beneficiaryName,
        String beneficiaryAccount,
        String beneficiaryAccountType,
        String beneficiaryTaxId,
        Integer beneficiaryInstitution,
        String concept,
        Long numericReference,
        String paymentType,
        String correlationId,
        Instant occurredOn
) {}
