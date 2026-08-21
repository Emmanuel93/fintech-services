package com.fintech.origination.domain;

/**
 * Quién emitió el veredicto sobre una evidencia.
 *
 * <p>Se guarda junto al veredicto y no se deduce de la configuración vigente: la bandera del
 * proveedor cambia con el tiempo y un expediente de hace seis meses tiene que poder decir **quién
 * lo revisó entonces**. Deducirlo del flag actual reescribiría la historia cada vez que se cambia
 * una variable de entorno, y en un expediente regulatorio eso no es un detalle.
 */
public enum VerificationSource {
    /** Lo revisó una persona. Hoy es el único origen posible: no hay contrato con proveedor. */
    MANUAL,
    /** Lo resolvió el proveedor de KYC dentro de sus umbrales, sin intervención humana. */
    PROVIDER,
    /**
     * El proveedor lo evaluó pero no alcanzó umbral, y una persona resolvió encima.
     *
     * <p>Es distinto de {@code MANUAL}: hubo señal automática y alguien decidió a pesar de ella.
     * Mezclarlos borraría exactamente el dato que sirve para calibrar los umbrales.
     */
    PROVIDER_ESCALATED
}
