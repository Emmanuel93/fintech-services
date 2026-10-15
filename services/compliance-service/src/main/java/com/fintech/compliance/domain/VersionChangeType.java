package com.fintech.compliance.domain;

/**
 * Qué le pasó a una entrada entre una versión de lista y la anterior.
 *
 * <p>Es el vocabulario con el que se contesta «¿por qué esta persona quedó señalada el 14?». Sin
 * él, la respuesta exige comparar las dos publicaciones a mano.
 */
public enum VersionChangeType {

    /** No estaba y ahora sí. Es el cambio que puede señalar a alguien que ayer estaba limpio. */
    ADDED,

    /** Estaba y ya no. Puede despejar a alguien que llevaba meses bloqueado. */
    REMOVED,

    /** Sigue estando, con datos distintos: corrigieron una fecha, cambió el programa. */
    MODIFIED,

    /**
     * La primera carga de una lista.
     *
     * <p>No se detalla entrada por entrada —serían cientos de miles de filas diciendo «todo es
     * nuevo»— sino con una sola fila y el total. Es la línea base; lo que un revisor mira son los
     * cambios contra ella.
     */
    INITIAL_LOAD
}
