package com.fintech.disbursement.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Resultado que reporta un conector, ya traducido.
 *
 * <p>Sin códigos de Banxico, sin {@code claveRastreo}, sin nada de STP: si mañana entra otro
 * proveedor, encaja aquí sin tocar el núcleo. Ese es todo el propósito de este tipo.
 */
public sealed interface ProviderOutcome {

    UUID disbursementId();

    /** El proveedor registró la orden. NO significa que el dinero haya salido (DB-03). */
    record Accepted(UUID disbursementId, String externalRef, Instant observedAt) implements ProviderOutcome {}

    /** El dinero llegó, con evidencia. Único resultado que lo afirma. */
    record Settled(UUID disbursementId, String externalRef, String receiptUrl,
                   boolean beneficiaryNameMatches, Instant settledAt) implements ProviderOutcome {}

    /**
     * El proveedor no aceptó la orden.
     *
     * @param retryable si es transitorio, la orden vuelve a la cola en vez de terminar (DB-08)
     */
    record Rejected(UUID disbursementId, String providerCode, String reason,
                    boolean retryable, Instant observedAt) implements ProviderOutcome {}

    /** El banco receptor devolvió el dinero después de haberse liquidado. */
    record Returned(UUID disbursementId, String causeCode, Instant observedAt) implements ProviderOutcome {}
}
