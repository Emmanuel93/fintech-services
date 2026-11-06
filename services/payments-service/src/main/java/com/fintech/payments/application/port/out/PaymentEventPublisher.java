package com.fintech.payments.application.port.out;

import com.fintech.payments.domain.PaymentOrder;
import com.fintech.payments.domain.balance.CustomerBalanceMovement;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentEventPublisher {
    void publishPaymentApplied(String eventId, UUID creditAccountId, BigDecimal amount, long snapshotVersion);

    /**
     * {@code payments.payment-applied} with where the money came from and, for a received credit, its two dates, its
     * tracking key and its place in the prelation: what the portfolio, treasury and accounting need to date it.
     */
    void publishPaymentApplied(PaymentOrder order);

    /** {@code payments.customer-balance-moved}: accounting books the payer's balance in favor (2102) from it. */
    void publishBalanceMoved(CustomerBalanceMovement movement);
    void publishPaymentReversed(String eventId, UUID creditAccountId, BigDecimal amount, String reason);
}
