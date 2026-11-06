package com.fintech.payments.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Outbound {@code payments.payment-returned}. {@code valueDate}: the business day the money went back. */
public record PaymentReversedOutboundPayload(
        String eventId,
        UUID creditAccountId,
        BigDecimal amount,
        String reason,
        LocalDate valueDate
) {
    public PaymentReversedOutboundPayload(String eventId, UUID creditAccountId, BigDecimal amount, String reason) {
        this(eventId, creditAccountId, amount, reason, null);
    }
}
