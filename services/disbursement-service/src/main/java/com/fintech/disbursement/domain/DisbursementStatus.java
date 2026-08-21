package com.fintech.disbursement.domain;

import java.util.Set;

/**
 * <pre>
 *   REQUESTED ──► DISPATCHED ──► ACCEPTED ──► SETTLED    (terminal, éxito)
 *       │              │             │
 *       │              │             └──────► RETURNED   (terminal, devuelto por el banco receptor)
 *       │              └────────────────────► REJECTED   (terminal, el proveedor rechazó el registro)
 *       └───────────────────────────────────► FAILED     (terminal, validación local o sin routing)
 *
 *   CANCELLED  (sólo desde REQUESTED, por operación y con motivo)
 * </pre>
 *
 * <p><strong>ACCEPTED no es dinero entregado</strong> (DB-03). Sólo {@code SETTLED} lo afirma, y
 * sólo con evidencia del proveedor.
 */
public enum DisbursementStatus {

    REQUESTED, DISPATCHED, ACCEPTED, SETTLED, REJECTED, RETURNED, FAILED, CANCELLED;

    private static final Set<DisbursementStatus> TERMINAL =
            Set.of(SETTLED, REJECTED, RETURNED, FAILED, CANCELLED);

    public boolean isTerminal() { return TERMINAL.contains(this); }
}
