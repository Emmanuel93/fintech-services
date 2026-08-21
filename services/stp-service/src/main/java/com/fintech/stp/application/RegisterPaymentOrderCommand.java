package com.fintech.stp.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Orden de pago SPEI a registrar. Vocabulario de pagos, no de crédito ni de desembolso: quien la
 * origina es asunto de quien publica el evento.
 */
public record RegisterPaymentOrderCommand(
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
        String correlationId
) {}
