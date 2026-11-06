package com.fintech.accounting.application.service;

import com.fintech.accounting.application.port.out.PayoutLinks;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Learns what an outgoing order pays out (a loan, or a lot of commissions), from disbursement's acceptance of the order.
 * Only the link is kept: the money moves with the bank's report of the order (treasury's cash book), never with this
 * fact.
 */
@Service
public class PayoutLinkProjection {

    private final PayoutLinks links;

    public PayoutLinkProjection(PayoutLinks links) {
        this.links = links;
    }

    /** A loan's order: the link the 2103 item of the disposition is paired by. */
    public void onOrderAccepted(UUID paymentRequestId, UUID creditAccountId, String sourceReference, BigDecimal amount) {
        if (paymentRequestId == null) return;
        links.learn(new PayoutLinks.Link(paymentRequestId, creditAccountId, null, sourceReference, amount));
    }

    /** The order of another item, such as a lot of commissions ({@code LOTE:<id>}): its 2103 item is the item itself. */
    public void onItemOrderAccepted(UUID paymentRequestId, String itemRef, String sourceReference, BigDecimal amount) {
        if (paymentRequestId == null || itemRef == null) return;
        links.learn(new PayoutLinks.Link(paymentRequestId, null, itemRef, sourceReference, amount));
    }
}
