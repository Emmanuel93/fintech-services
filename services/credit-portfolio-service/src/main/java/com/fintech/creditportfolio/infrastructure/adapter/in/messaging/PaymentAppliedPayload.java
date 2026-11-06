package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.creditportfolio.domain.PaymentFacts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Inbound from payments-service (topic {@code payments.payment-applied}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentAppliedPayload(
        String eventId,
        UUID creditAccountId,
        BigDecimal amount,
        /** When the money was received: the payment is dated that day while it is still open. */
        Instant receivedAt,
        /** The SPEI operation date: the day the bank books it. Null when no money moved (balance in favor). */
        LocalDate operationDate,
        String fundingSource,
        UUID inboundCreditId,
        String allocationMode,
        String trackingKey
) {
    public PaymentFacts facts() {
        return new PaymentFacts(receivedAt, operationDate, fundingSource, inboundCreditId, allocationMode, trackingKey);
    }
}
