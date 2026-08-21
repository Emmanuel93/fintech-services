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
    /** El banco receptor devolvió el dinero. */
    RETURNED_BY_BENEFICIARY_BANK,
    /** No se pudo entregar la orden al conector. Transitorio: no termina la orden por sí solo. */
    PROVIDER_UNAVAILABLE,
    /** Se agotaron los intentos de despacho. */
    MAX_ATTEMPTS_EXCEEDED,
    /** Operación canceló la orden antes de despacharla. */
    CANCELLED_BY_OPERATOR
}
