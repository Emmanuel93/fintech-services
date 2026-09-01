package com.fintech.banking.domain;

/**
 * Quién ejecuta el pago. Añadir uno es una fila en {@code payout_routes}, un valor aquí y un
 * conector nuevo — ningún cambio en el dominio.
 */
public enum PayoutProvider {
    STP
}
