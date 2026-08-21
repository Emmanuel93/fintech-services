package com.fintech.beneficiary.domain;

/**
 * Quién emitió el veredicto de identidad.
 *
 * <p>Se guarda con el veredicto y no se deduce de la bandera vigente: la configuración cambia y un
 * expediente de hace seis meses tiene que poder decir <b>quién lo revisó entonces</b>. Deducirlo
 * del flag actual reescribiría la historia cada vez que se toca una variable de entorno.
 */
public enum VerificationSource {
    /** Lo revisó una persona. Hoy es el único origen: no hay contrato con proveedor de KYC. */
    MANUAL,
    /** Lo resolvió el proveedor dentro de sus umbrales, sin intervención humana. */
    PROVIDER,
    /** El proveedor no alcanzó umbral y una persona resolvió encima. */
    PROVIDER_ESCALATED
}
