package com.fintech.stp.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Orden de pago SPEI a registrar. Vocabulario de pagos, no de crédito ni de desembolso: quien la
 * origina es asunto de quien publica el evento.
 *
 * <p><b>La cuenta ordenante viene en la orden</b> desde BK-07. Este conector ya no elige por dónde
 * sale el dinero: elegía con un {@code is_default} por empresa, ciego al saldo y al costo, y esa es
 * una decisión de tesorería. Los campos son opcionales mientras dura la migración — si llegan
 * vacíos se cae al catálogo local, y esa caída desaparece en BK-07b.
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
        String correlationId,
        UUID orderingAccountId,
        String orderingClabe,
        String orderingHolderName,
        String orderingTaxId,
        String orderingClientNumber
) {
    /** Si tesorería mandó la cuenta, este conector no tiene nada que elegir. */
    public boolean traeCuentaOrdenante() {
        return orderingClabe != null && !orderingClabe.isBlank();
    }
}
