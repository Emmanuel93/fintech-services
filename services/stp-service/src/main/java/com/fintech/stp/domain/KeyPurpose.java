package com.fintech.stp.domain;

/**
 * Para qué sirve una llave de {@code stp.company_keys}.
 *
 * <p>El puerto de custodia es simétrico a propósito: firmar lo que sale y verificar lo que entra
 * son la misma responsabilidad — custodia de material criptográfico por empresa.
 */
public enum KeyPurpose {
    /** Nuestra llave privada, con la que firmamos las órdenes. Material envuelto (envelope encryption). */
    SIGNING,
    /** La llave pública de STP, con la que verificamos el sello de sus respuestas. En claro: es pública. */
    VERIFICATION
}
