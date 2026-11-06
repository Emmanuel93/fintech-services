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
 *
 * <p>A persisted SENT is an order whose registration never got an answer: sending and reading the answer happen in one
 * transaction, so SENT outlives it only when the relay gave up on a silent STP. STP may hold the order all the same,
 * so it is not failed: the reconciliation of its operation date settles it, or fails it once that day closed without it.
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
