package com.fintech.receipt.infrastructure.adapter.out.messaging;

import com.fintech.receipt.domain.Receipt;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** {@code receipt.receipt-generated}: a client's receipt for a statement of his distributor's line is ready. */
public record ReceiptGeneratedPayload(String eventId, UUID receiptId, UUID creditAccountId, int cycleNumber,
                                      UUID beneficiaryPartyId, UUID distributorPartyId, String folio, BigDecimal totalDue,
                                      LocalDate paymentDueDate, Long paymentReference, Instant occurredOn) {

    static ReceiptGeneratedPayload of(Receipt r) {
        return new ReceiptGeneratedPayload(UUID.randomUUID().toString(), r.receiptId(), r.creditAccountId(), r.cycleNumber(),
                r.beneficiaryPartyId(), r.distributorPartyId(), r.folio(), r.totalDue(), r.paymentDueDate(),
                r.paymentReference(), Instant.now());
    }
}
