package com.fintech.stp.domain;

/** Por qué vía nos enteramos del desenlace de una orden. Sirve para auditar la latencia real. */
public enum ObservedVia {
    /** Poller periódico sobre {@code V2/conciliacion}. La vía normal. */
    POLL_RECONCILIATION,
    /** Consulta puntual por clave de rastreo, si STP la soporta. */
    POLL_ORDER,
    /** Barrido de cierre de día. La red de seguridad. */
    EOD_BATCH,
    /** Carga manual de operación, con evidencia. */
    MANUAL
}
