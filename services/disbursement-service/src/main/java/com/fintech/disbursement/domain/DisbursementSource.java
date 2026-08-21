package com.fintech.disbursement.domain;

/**
 * De dónde nació la orden. Es una etiqueta, no una dependencia: el núcleo no cambia de
 * comportamiento según el valor (DB-09).
 */
public enum DisbursementSource {
    /** Disposición de una línea de crédito. */
    DISPOSITION,
    /** Retiro de un saldo a favor del cliente. */
    WITHDRAWAL,
    /** Devolución de un sobrepago. */
    SURPLUS_RETURN,
    /** Micro-abono para validar titularidad de una cuenta. */
    ACCOUNT_VERIFICATION,
    /** Entrada por la API de producto, con Idempotency-Key. */
    API,
    /** Alta manual de operación, con motivo. */
    MANUAL
}
