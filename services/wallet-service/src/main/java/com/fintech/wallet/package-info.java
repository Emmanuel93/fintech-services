/**
 * D7 — Wallet [Supporting]
 *
 * <p>Proyección read-only del estado de CreditProduct para la UI.
 * <strong>No es fuente de verdad.</strong> Sincroniza en cada evento relevante de D4.
 * Validación fail-fast: {@code amount ≤ availableCredit} antes de enviar a CreditProduct.
 * {@code WalletSnapshotUpdated} consolida múltiples eventos en un solo refresh de UI.
 *
 * <p>Schema DB: {@code wallet}
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"}
)
package com.fintech.wallet;
