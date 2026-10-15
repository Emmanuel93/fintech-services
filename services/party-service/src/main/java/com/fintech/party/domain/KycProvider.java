package com.fintech.party.domain;

/**
 * Cuál proveedor resolvió la verificación.
 *
 * <p>Existe porque «lo resolvió un proveedor» deja de ser una respuesta en cuanto hay más de uno.
 * El día que convivan dos, un expediente tiene que poder decir con cuál se hizo — para reabrirlo,
 * para auditarlo, y para saber a quién reclamarle.
 *
 * <p>{@link #NONE} no es un hueco: es el veredicto legítimo de un KYC que resolvió una persona.
 */
public enum KycProvider {
    NONE,
    INCODE,
    JUMIO
}
