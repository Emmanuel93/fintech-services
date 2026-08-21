package com.fintech.notifications.domain;

public enum EventType {
    OFFER_PRESENTED,
    WELCOME_ACTIVATED,
    DISBURSEMENT_COMPLETED,
    PAYMENT_REMINDER,
    /** La mensualidad venció y sigue sin cubrirse. */
    PAYMENT_OVERDUE,
    INSTALLMENT_PAID,
    LOAN_SETTLED,

    // ── Cobranza ─────────────────────────────────────────────────────────────
    // Cinco escalones de tono, uno por día, durante los primeros cinco días de mora. Son tipos
    // distintos y no un solo COLLECTION_DUNNING con variable de tono porque cada uno lleva su
    // plantilla y su política: el que menciona el historial crediticio no puede salir por el mismo
    // canal ni con el mismo texto que el que sólo recuerda una fecha.

    /** Día 1 — «parece que se te pasó». Sin consecuencias, sin urgencia. */
    COLLECTION_REMINDER,
    /** Día 2 — mismo tono, con las formas de pagar a la mano. */
    COLLECTION_HOW_TO_PAY,
    /** Día 3 — se nombra el atraso y lo que se acumula si sigue. */
    COLLECTION_OVERDUE_NOTICE,
    /**
     * Día 4 — única mención al historial crediticio, y en positivo: ponerse al corriente lo protege.
     *
     * <p>No promete evitar el reporte. El atraso se informa a las sociedades de información
     * crediticia porque es un hecho del crédito, no una decisión de cobranza, así que no hay nada
     * que ofrecer a cambio. Usarlo como palanca —«si no pagas hoy te reportamos»— es exactamente la
     * práctica que se sanciona.
     */
    COLLECTION_CREDIT_HISTORY,
    /** Día 5 — cierra el ciclo automático ofreciendo hablar y buscar opciones. */
    COLLECTION_OFFER_HELP,

    /** Se cumplió lo prometido. El único mensaje de cobranza que no pide nada. */
    COLLECTION_PAYMENT_THANKS,

    /** Se le ofrece formalmente un convenio, con su vigencia y su liga de aceptación. */
    COLLECTION_AGREEMENT_OFFERED,
    /** El convenio quedó firme. */
    COLLECTION_AGREEMENT_EXECUTED
}
