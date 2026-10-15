package com.fintech.party.domain;

/**
 * Quién emitió el veredicto de identidad.
 *
 * <p>Se guarda con el veredicto y <b>no se deduce</b> de la configuración vigente: la política
 * cambia, y un expediente de hace seis meses tiene que poder decir quién lo revisó <em>entonces</em>.
 * Deducirlo del modo actual reescribiría la historia cada vez que alguien toca una variable.
 *
 * <p>Es el mismo criterio que ya aplica {@code beneficiary}; esta copia existe porque el modelo
 * canónico —el que cubre a todo el que queda registrado— vive aquí.
 */
public enum VerificationSource {
    /** Lo revisó una persona. */
    MANUAL,
    /** Lo resolvió el proveedor dentro de sus umbrales, sin intervención humana. */
    PROVIDER,
    /** El proveedor no alcanzó umbral y una persona resolvió encima. */
    PROVIDER_ESCALATED
}
