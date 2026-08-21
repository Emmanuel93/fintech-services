package com.fintech.stp.application.port.in;

import com.fintech.stp.application.RegisterPaymentOrderCommand;

public interface RegisterPaymentOrderUseCase {

    /**
     * Registra una orden de pago. Idempotente por {@code paymentRequestId}: reprocesar el mismo
     * mensaje no crea una segunda orden ni produce una segunda llamada a STP.
     *
     * <p>No habla con STP: persiste la orden y encola el envío en el outbox.
     */
    void register(RegisterPaymentOrderCommand command);
}
