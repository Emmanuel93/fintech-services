package com.fintech.stp.application.port.out;

import com.fintech.stp.domain.BanxicoResponseCode;
import com.fintech.stp.domain.ObservedVia;
import com.fintech.stp.domain.StpPaymentOrder;

import java.time.Instant;

/**
 * Publicación del resultado. Los eventos que salen hablan de órdenes de pago, no de STP: ni
 * {@code claveRastreo} ni {@code firma} ni {@code empresa} cruzan la frontera con ese nombre.
 */
public interface StpEventPublisher {

    /** STP aceptó el registro. No significa que el dinero haya salido. */
    void publishOrderAccepted(StpPaymentOrder order);

    void publishOrderRejected(StpPaymentOrder order, BanxicoResponseCode code, String detail);

    /** El dinero llegó. */
    void publishOrderSettled(StpPaymentOrder order, Instant settledAt, String cepUrl,
                             String cepBeneficiaryName, boolean beneficiaryNameMatches,
                             ObservedVia observedVia);

    void publishOrderReturned(StpPaymentOrder order, String status, String returnCauseCode,
                              ObservedVia observedVia);
}
