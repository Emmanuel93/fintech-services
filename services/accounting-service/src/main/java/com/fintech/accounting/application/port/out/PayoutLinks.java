package com.fintech.accounting.application.port.out;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What each outgoing SPEI order pays out: a loan, or another item such as a lot of commissions. The bank reports an
 * order (the connector's payment request) and the portfolio a disposition, or commission a lot: the link pairs them, at
 * the close, whatever order the facts arrived in.
 */
public interface PayoutLinks {

    /**
     * @param creditAccountId the loan the order pays out; null for an order that pays something else
     * @param itemRef         that something else ({@code LOTE:<id>}); null for a loan's order
     */
    record Link(UUID paymentRequestId, UUID creditAccountId, String itemRef, String sourceReference, BigDecimal amount) {}

    /** Keeps the link of an order; the first one learned stays (a redelivery brings the same). */
    void learn(Link link);
}
