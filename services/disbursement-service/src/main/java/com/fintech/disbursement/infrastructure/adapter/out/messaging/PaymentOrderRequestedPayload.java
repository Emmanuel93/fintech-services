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
        /**
         * La cuenta de la que sale el dinero, decidida por <b>tesorería</b>. Viaja en el mensaje y
         * no como un id a resolver: si fuera un id, el conector necesitaría su propia copia del
         * catálogo de cuentas propias — que es exactamente de donde venimos.
         */
        UUID orderingAccountId,
        String orderingClabe,
        String orderingHolderName,
        String orderingTaxId,
        String orderingClientRef,
        Instant occurredOn
) {}
