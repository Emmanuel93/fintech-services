package com.fintech.stp.domain;

public enum KeyStatus {
    /** La que se usa. Sólo puede haber una por empresa y propósito. */
    ACTIVE,
    /** Dada de alta y esperando el corte. Permite rotar sin ventana de indisponibilidad. */
    ROTATING,
    /** Reemplazada. Se conserva para poder verificar firmas históricas. */
    RETIRED,
    /** Comprometida. Nunca se vuelve a usar. */
    REVOKED
}
