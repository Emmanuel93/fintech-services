package com.fintech.disbursement.application.port.in;

import com.fintech.disbursement.application.RequestDisbursementCommand;
import com.fintech.disbursement.domain.DisbursementOrder;

import java.util.Optional;

public interface RequestDisbursementUseCase {

    /**
     * Registra la orden y la deja lista para despacho. <strong>No habla con ningún proveedor</strong>:
     * eso lo hace {@link DispatchDisbursementsUseCase} fuera de esta transacción.
     *
     * <p>Idempotente por {@code (sourceSystem, sourceType, sourceEventId)}: reenviar el mismo evento
     * devuelve la orden existente sin crear una segunda.
     */
    DisbursementOrder request(RequestDisbursementCommand command);

    /**
     * The same, for the entry points that hear a fact instead of answering a call. A request the instruction itself
     * makes impossible (an invalid CLABE, a missing field, no company for its origin) has nobody to answer: it used to
     * go to the dead letters, and the caller's disposition waited for an outcome forever (D67). It is answered with
     * {@code disbursement.failed}, its failure code and the caller's keys echoed, and no order.
     *
     * @return the order, or empty when the request was rejected
     */
    Optional<DisbursementOrder> requestOrReject(RequestDisbursementCommand command);
}
