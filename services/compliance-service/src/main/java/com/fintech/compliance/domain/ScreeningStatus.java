package com.fintech.compliance.domain;

/**
 * El veredicto de listas de una persona.
 *
 * <h2>Tres estados y no dos, y el del medio es el que hace operable el control</h2>
 *
 * El cruce se hace por nombre —CURP y RFC casi nunca vienen en las listas— y el nombre no
 * identifica. Un veredicto binario tiene dos salidas y las dos son malas: con el umbral bajo se
 * bloquea a media cartera por homonimia, con el umbral alto no se bloquea a nadie y el control es
 * decorativo.
 *
 * <p>{@link #POTENTIAL_MATCH} rompe ese dilema: <b>bloquea igual que un confirmado</b>, pero es
 * resoluble por una persona y deja rastro de quién lo resolvió y con qué argumento.
 */
public enum ScreeningStatus {

    /** Todavía no se ha corrido. <b>Bloquea</b>: no haber comprobado no es haber salido limpio. */
    PENDING,

    /** Se corrió y no hay candidatos por encima del umbral. */
    CLEARED,

    /** Hay candidatos sin despejar. Bloquea, y espera a que cumplimiento adjudique. */
    POTENTIAL_MATCH,

    /** Una persona confirmó que es la misma. Bloquea, y es definitivo. */
    CONFIRMED_MATCH;

    /**
     * Sólo {@code CLEARED} deja pasar.
     *
     * <p>Se escribe en positivo a propósito. La forma natural —«bloquea si es POTENTIAL o
     * CONFIRMED»— es la que se rompe en silencio el día que alguien añade un estado: el nuevo cae
     * fuera de la lista y pasa sin que nadie lo decida.
     */
    public boolean allowsToProceed() {
        return this == CLEARED;
    }
}
