package com.fintech.stp.domain;

/** Qué pasó con una observación de liquidación traída de STP (reglas SO-01..SO-06). */
public enum SettlementObservationStatus {
    /** Se aplicó a la orden y se publicó el evento correspondiente. */
    APPLIED,
    /** Ya la habíamos visto. El poller relee lo mismo cada N minutos por diseño (SO-02). */
    DUPLICATE,
    /** No corresponde a ninguna orden nuestra. No se descarta: se alerta (SO-03). */
    UNMATCHED,
    /** El sello no verifica contra la llave pública de STP. No se aplica (SO-04). */
    SIGNATURE_INVALID,
    /**
     * No se pudo verificar el sello —no hay llave de verificación registrada, o la política es
     * {@code WARN} y la cadena firmada por STP todavía no está confirmada contra su
     * especificación— y el cambio de estado <strong>sí</strong> se aplicó.
     *
     * <p>Existe porque la alternativa es peor: bloquear todas las liquidaciones contra una
     * suposición sobre el formato del sello significa que el dinero que ya salió nunca se confirma.
     * Es un estado ruidoso a propósito: alguien tiene que cerrar la brecha.
     */
    SIGNATURE_UNVERIFIED,
    /** Error al procesarla. Queda para reintento. */
    FAILED
}
