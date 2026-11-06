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

    /**
     * STP did not take the order, and the payment request ends here. Final even for a transient code: the connector is
     * the one that retries what is transient, and it publishes only once it gave up. A caller that retried the same
     * request would meet the idempotency guard and hear nothing back. It says whether the cause was technical (a
     * transient code exhausted) or a rule of the bank (PLD, an invalid account): a new request may succeed after the
     * first, never after the second.
     */
    void publishOrderRejected(StpPaymentOrder order, BanxicoResponseCode code, String detail);

    /**
     * STP never registered the order: no attempt got an answer and the reconciliation of its closed operation date
     * does not list it. Final as well, and technical; the caller may pay again with a new request without paying
     * twice.
     */
    void publishOrderNotRegistered(StpPaymentOrder order, String detail);

    /** El dinero llegó. */
    void publishOrderSettled(StpPaymentOrder order, Instant settledAt, String cepUrl,
                             String cepBeneficiaryName, boolean beneficiaryNameMatches,
                             ObservedVia observedVia);

    void publishOrderReturned(StpPaymentOrder order, String status, String returnCauseCode,
                              ObservedVia observedVia);
}
