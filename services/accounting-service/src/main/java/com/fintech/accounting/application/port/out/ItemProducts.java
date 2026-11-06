package com.fintech.accounting.application.port.out;

import java.util.Optional;

/**
 * The product of a voucher that has no loan, from the item it moves on a clearing account: the bank side of an order
 * is the product of the loan or the commission lot the order paid out; the bank side of a received credit, the product
 * the portfolio applied it to. Empty when the item is not known yet (its link or its application has not arrived) or
 * when it belongs to more than one product.
 */
public interface ItemProducts {

    /** The product of the order whose payment request is {@code paymentRequestId}. */
    Optional<String> ofOrder(String paymentRequestId);

    /** The one product a received credit was applied to; empty when it was applied to several, or not yet. */
    Optional<String> ofReceivedCredit(String creditRef);

    /**
     * Attributes what became known after it was booked, in open months only (a closed month keeps what it closed
     * with): vouchers of loans whose product arrived late, and the bank side of orders and received credits whose item
     * is known now. Idempotent: it only fills what is empty.
     *
     * @return the vouchers attributed
     */
    int sweepOpenPeriods();
}
