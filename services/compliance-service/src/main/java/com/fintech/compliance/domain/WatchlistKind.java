package com.fintech.compliance.domain;

/**
 * Qué clase de lista es, porque no todas significan lo mismo ni bloquean igual.
 *
 * <p>Meterlas todas en un saco llamado «lista negra» pierde la distinción que decide qué se hace con
 * una coincidencia: estar sancionado y ser una persona políticamente expuesta son hechos de
 * naturaleza distinta. El primero impide operar; el segundo obliga a **debida diligencia
 * reforzada** — más evidencia y una aprobación de más alto nivel— pero no prohíbe nada por sí solo.
 * Tratar a un PEP como sancionado es negarle servicio a un funcionario público por serlo, y eso no
 * es cumplimiento: es un error.
 */
public enum WatchlistKind {

    /** Sancionados: personas y entidades con prohibición de operar. Una coincidencia confirmada corta. */
    SANCTIONS,

    /** Personas políticamente expuestas y sus allegados. Obliga a diligencia reforzada, no prohíbe. */
    PEP,

    /**
     * Contribuyentes con operaciones presuntamente inexistentes (SAT, art. 69-B) y equivalentes.
     * Es un hecho fiscal, no de identidad: pesa en la decisión de crédito, no en la de identidad.
     */
    TAX_BLACKLIST,

    /** Lista interna de la institución: fraude comprobado, quebranto doloso. Decisión propia. */
    INTERNAL
}
