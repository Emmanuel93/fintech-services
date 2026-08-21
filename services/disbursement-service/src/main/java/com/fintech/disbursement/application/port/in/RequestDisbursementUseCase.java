package com.fintech.disbursement.application.port.in;

import com.fintech.disbursement.application.RequestDisbursementCommand;
import com.fintech.disbursement.domain.DisbursementOrder;

public interface RequestDisbursementUseCase {

    /**
     * Registra la orden y la deja lista para despacho. <strong>No habla con ningún proveedor</strong>:
     * eso lo hace {@link DispatchDisbursementsUseCase} fuera de esta transacción.
     *
     * <p>Idempotente por {@code (sourceSystem, sourceType, sourceEventId)}: reenviar el mismo evento
     * devuelve la orden existente sin crear una segunda.
     */
    DisbursementOrder request(RequestDisbursementCommand command);
}
