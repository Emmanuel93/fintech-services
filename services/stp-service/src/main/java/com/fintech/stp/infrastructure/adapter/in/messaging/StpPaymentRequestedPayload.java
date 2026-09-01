package com.fintech.stp.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Orden de pago SPEI a registrar.
 *
 * <p>Ni una palabra del dominio de crédito ni del de desembolso: quien la origina es asunto de
 * quien publica. Eso es lo que permite vender este servicio por separado.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StpPaymentRequestedPayload(
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
        /** La cuenta de la que sale el dinero, decidida por tesorería (BK-07). */
        UUID orderingAccountId,
        String orderingClabe,
        String orderingHolderName,
        String orderingTaxId,
        String orderingClientRef,
        Instant occurredOn
) {}
