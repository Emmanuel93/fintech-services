package com.fintech.beneficiary.domain;

/**
 * Cómo se verifica la identidad. Lo decide una bandera de configuración.
 *
 * <p><b>La bandera controla el flujo, no el arranque.</b> Cualquiera de los dos modos produce un
 * servicio que funciona; lo que cambia es si algo intenta resolver antes de llegar a una persona.
 */
public enum IdentityVerificationMode {

    /**
     * Todo lo revisa un analista de crédito. Es el modo vigente: no hay contrato con proveedor.
     *
     * <p>No se llama a nadie. No es una limitación provisional sino la política: sin proveedor
     * contratado, aprobar automáticamente sería aprobar sin fundamento.
     */
    MANUAL,

    /**
     * El proveedor de KYC evalúa primero y el analista recibe sólo las excepciones.
     *
     * <p>«Excepción» es todo lo que el proveedor no resolvió con confianza: un umbral no alcanzado,
     * un documento que no pudo validar, o <b>el proveedor caído</b>. Los tres terminan en el mismo
     * lugar —la cola del analista— porque desde el punto de vista del negocio son el mismo hecho:
     * esto necesita ojo humano.
     */
    AUTOMATIC
}
