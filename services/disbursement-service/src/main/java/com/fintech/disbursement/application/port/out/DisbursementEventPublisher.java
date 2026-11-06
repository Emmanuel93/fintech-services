package com.fintech.disbursement.application.port.out;

import com.fintech.disbursement.application.RequestDisbursementCommand;
import com.fintech.disbursement.domain.DisbursementOrder;
import com.fintech.disbursement.domain.FailureCode;

/**
 * Hechos que publica este servicio. Cuatro, y ninguno es un comando:
 *
 * <ul>
 *   <li>{@code disbursement.accepted}  — el proveedor tomó la orden (operación y auditoría)</li>
 *   <li>{@code disbursement.completed} — el dinero llegó, con evidencia</li>
 *   <li>{@code disbursement.failed}    — no va a llegar</li>
 *   <li>{@code disbursement.returned}  — llegó y el banco receptor lo devolvió</li>
 * </ul>
 *
 * <p>Todos llevan de vuelta {@code sourceSystem}, {@code sourceReference}, {@code sourceEventId} y
 * {@code sourceMetadata} en eco, para que el emisor correlacione sin que este servicio entienda nada
 * de lo que hay dentro.
 */
public interface DisbursementEventPublisher {

    void publishAccepted(DisbursementOrder order);

    void publishCompleted(DisbursementOrder order, boolean beneficiaryNameMatches);

    void publishFailed(DisbursementOrder order);

    /**
     * {@code disbursement.failed} for a request that never became an order: the instruction itself made it impossible
     * (D67). No disbursement id; the caller's keys and metadata go back so it correlates.
     */
    void publishRequestRejected(RequestDisbursementCommand command, FailureCode code, String reason);

    void publishReturned(DisbursementOrder order);
}
