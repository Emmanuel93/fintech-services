package com.fintech.compliance.domain;

/**
 * El ciclo de una carga.
 *
 * <p>`LOADED` y `ACTIVE` están separados a propósito: terminar de leer un archivo y pasar a ser la
 * lista con la que se decide son dos hechos distintos, y entre ellos cabe una comprobación —por
 * ejemplo, que la tasa de rechazo no se haya disparado respecto a la carga anterior—. Fundirlos
 * quitaría el único punto donde se puede frenar una carga sospechosa antes de que decida sobre
 * personas.
 */
public enum WatchlistVersionStatus {
    /** Leyendo el archivo. Nadie la consulta. */
    LOADING,
    /** Leída y completa, todavía sin efecto. */
    LOADED,
    /** Es la lista vigente: contra ésta se decide. */
    ACTIVE,
    /** Fue vigente y la reemplazó otra. Se conserva para explicar veredictos pasados. */
    SUPERSEDED,
    /** No se pudo cargar. Nunca tuvo efecto. */
    FAILED
}
