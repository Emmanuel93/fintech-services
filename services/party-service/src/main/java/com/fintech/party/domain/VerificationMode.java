package com.fintech.party.domain;

/**
 * Cómo se intentó resolver una verificación de identidad.
 *
 * <p><b>La exigencia la decide el producto, no una bandera del servicio.</b> Una línea de
 * distribuidor y una tarjeta de uso propio no piden lo mismo, y con una bandera global el día que
 * un producto pida verificación reforzada se la aplica a todos.
 *
 * <p>Por eso este enum guarda <b>lo que ocurrió</b> y no lo que la política dice hoy: {@code party}
 * no conoce productos ni debe conocerlos. Quien resuelve la exigencia es el subdominio que sí lo
 * sabe —originación para uso propio, colocación para el modelo distribuidor— y aquí llega ya
 * resuelto.
 */
public enum VerificationMode {
    /** Se intentó con proveedor. Puede haber terminado igualmente en manos de una persona. */
    AUTOMATIC,
    /** Fue a una persona desde el principio, por política del producto o por falta de proveedor. */
    MANUAL
}
