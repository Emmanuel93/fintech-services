package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Inbound {@code credit-portfolio.credit-account-activated} — el alta del préstamo.
 *
 * <p>Hasta ahora accounting no lo escuchaba, así que la primera huella contable de un crédito era la
 * disposición del dinero: un crédito autorizado y no dispuesto no existía en la contabilidad. Este
 * evento es también el que trae la <b>sucursal de origen</b>, que a partir de aquí llevan todas las
 * pólizas del préstamo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditAccountActivatedPayload(
        String eventId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String contractNumber,
        /** El límite de la línea. **Nulo en un crédito simple**: ahí el monto es el capital dispuesto. */
        BigDecimal creditLimit,
        /**
         * El capital que se coloca al activar.
         *
         * <p>El nombre importa: el evento publica {@code principalBalance}, y declararlo como
         * {@code principalAmount} lo dejaba siempre nulo sin que nada fallara — el alta se descartaba
         * en silencio y todos los créditos acababan pareciendo incorporaciones de saldo previo.
         */
        BigDecimal principalBalance,
        String originUnitCode,
        Instant occurredOn
) {
    /**
     * Lo que se registra en cuentas de orden: la línea autorizada.
     *
     * <p>En una revolvente es el límite; en un crédito simple no hay límite y la línea autorizada es
     * el capital del contrato.
     */
    public BigDecimal authorizedAmount() {
        return creditLimit != null && creditLimit.signum() > 0 ? creditLimit : principalBalance;
    }
}
