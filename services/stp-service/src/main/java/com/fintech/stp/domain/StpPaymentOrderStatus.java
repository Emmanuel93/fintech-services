package com.fintech.stp.domain;

import java.util.Set;

/**
 * Ciclo de vida de una orden de pago frente a STP.
 *
 * <pre>
 *   PENDING ──► SENT ──► ACCEPTED ──► SETTLED     (terminal, el dinero llegó)
 *      │         │           │
 *      │         │           └──────► RETURNED    (terminal, el banco receptor devolvió)
 *      │         │           └──────► CANCELLED   (terminal)
 *      │         └──────────────────► REJECTED    (terminal, STP no aceptó el registro)
 *      └────────────────────────────► FAILED      (terminal, no se pudo ni enviar)
 * </pre>
 *
 * <p><strong>ACCEPTED no es dinero entregado.</strong> STP acepta el registro en segundos; la
 * liquidación se confirma después, por consulta.
 */
public enum StpPaymentOrderStatus {

    PENDING, SENT, ACCEPTED, SETTLED, REJECTED, RETURNED, CANCELLED, FAILED;

    private static final Set<StpPaymentOrderStatus> TERMINAL =
            Set.of(SETTLED, REJECTED, RETURNED, CANCELLED, FAILED);

    private static final Set<StpPaymentOrderStatus> IN_FLIGHT = Set.of(SENT, ACCEPTED);

    public boolean isTerminal() { return TERMINAL.contains(this); }

    /** Estados que el poller de liquidación tiene que seguir consultando. */
    public boolean isInFlight() { return IN_FLIGHT.contains(this); }
}
