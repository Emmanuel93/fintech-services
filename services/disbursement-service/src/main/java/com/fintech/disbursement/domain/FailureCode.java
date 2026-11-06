package com.fintech.disbursement.domain;

/**
 * Catálogo estable de motivos de salida. Es parte del contrato público de
 * {@code disbursement.failed}: quien lo consume decide qué hacer según el código, no según el texto.
 *
 * <p>Los códigos del proveedor <strong>no</strong> se filtran aquí. Un {@code -200} de Banxico llega
 * traducido a {@link #REJECTED_BY_PROVIDER} con el detalle en {@code failureReason}; el núcleo no
 * conoce el catálogo de ningún proveedor.
 */
public enum FailureCode {

    /** La cuenta del beneficiario no pasó la validación local (dígito verificador, longitud). */
    INVALID_BENEFICIARY_ACCOUNT,
    /** Falta un dato obligatorio para poder pagar: nombre, monto, moneda. */
    INVALID_INSTRUCTION,
    /** No hay regla de routing que cubra (empresa, rail, monto). */
    NO_ROUTING_RULE,
    /** No se pudo resolver la empresa a partir de la procedencia. */
    UNRESOLVED_COMPANY,
    /** El proveedor rechazó el registro de forma terminal. */
    REJECTED_BY_PROVIDER,
    /**
     * The provider could not execute the order for a technical reason: its link failed past its own retries, or it
     * never registered the order. The money did not leave, and a new request may succeed where this one could not.
     */
    PROVIDER_TECHNICAL_FAILURE,
    /** El banco receptor devolvió el dinero. */
    RETURNED_BY_BENEFICIARY_BANK,
    /** No se pudo entregar la orden al conector. Transitorio: no termina la orden por sí solo. */
    PROVIDER_UNAVAILABLE,
    /** Se agotaron los intentos de despacho. */
    MAX_ATTEMPTS_EXCEEDED,
    /** Operación canceló la orden antes de despacharla. */
    CANCELLED_BY_OPERATOR;

    /**
     * Whether a new request may succeed where this one could not: the money did not leave for a technical or a
     * configuration reason (the link, a route or a company missing, the connector out of reach), not for a rule of the
     * bank, the beneficiary or the instruction itself. The caller decides with it whether to ask again: a credit's
     * disposition is retried; a rule is compensated (D63).
     */
    public boolean anotherAttemptMayWork() {
        return switch (this) {
            case PROVIDER_TECHNICAL_FAILURE, PROVIDER_UNAVAILABLE, MAX_ATTEMPTS_EXCEEDED, NO_ROUTING_RULE,
                 UNRESOLVED_COMPANY -> true;
            case INVALID_BENEFICIARY_ACCOUNT, INVALID_INSTRUCTION, REJECTED_BY_PROVIDER, RETURNED_BY_BENEFICIARY_BANK,
                 CANCELLED_BY_OPERATOR -> false;
        };
    }
}
