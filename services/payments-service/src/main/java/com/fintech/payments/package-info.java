/**
 * D6 — Payments [Supporting]
 *
 * <p>Recepción y aplicación de pagos. <strong>Nunca modifica saldos directamente.</strong>
 * Emite {@code PaymentApplied} para que CreditProduct procese.
 * Idempotencia garantizada por {@code externalRef} (referencia única SPEI/CoDi).
 * Métodos: SPEI, CoDi, DOMICILIACION, VENTANILLA, TARJETA, INTERNAL_TRANSFER.
 *
 * <p>Schema DB: {@code payments}
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"}
)
package com.fintech.payments;
