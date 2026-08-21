package com.fintech.collections.domain;

/** Por qué está callada la cobranza automática de un caso. */
public enum HoldReason {
    /** Hay una promesa viva. Presionar a quien acaba de comprometerse destruye el compromiso. */
    ACTIVE_PROMISE,
    /** El deudor cumplió. Se le agradece y se le deja respirar antes de volver a insistir. */
    PROMISE_KEPT,
    /** Hay un convenio ofrecido o aceptado: no se negocia y se presiona a la vez. */
    AGREEMENT_IN_FLIGHT,
    /** El caso lo gestiona un despacho. Dos cobradores sobre la misma persona es la queja típica. */
    AGENCY_ASSIGNED,
    /** Lo puso un agente, con motivo. */
    MANUAL
}
