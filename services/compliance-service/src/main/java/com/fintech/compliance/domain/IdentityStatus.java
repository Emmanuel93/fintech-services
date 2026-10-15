package com.fintech.compliance.domain;

/**
 * El veredicto de identidad de una persona: ¿es quien dice ser?
 *
 * <p>Separado del de listas porque se resuelve por otro camino. Aquí la persona <b>sí</b> puede
 * hacer algo: repetir la selfie, subir una INE legible, volver a la prueba de vida.
 */
public enum IdentityStatus {

    /** Todavía no se ha evaluado. <b>Bloquea</b>: no haber comprobado no es haber comprobado bien. */
    PENDING,

    /**
     * En la cola de un analista.
     *
     * <p>Es el estado normal —no un error— cuando el producto no automatiza, cuando el proveedor no
     * resolvió dentro de sus umbrales, y cuando el proveedor no estuvo disponible. Bloquea.
     */
    IN_REVIEW,

    /** Comprobada. Es el único que deja pasar. */
    VERIFIED,

    /** No es quien dice ser. Bloquea, y la salida es volver a empezar con evidencia distinta. */
    REJECTED
}
