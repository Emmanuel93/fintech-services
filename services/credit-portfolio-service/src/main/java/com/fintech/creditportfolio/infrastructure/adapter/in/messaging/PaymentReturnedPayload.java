package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/** Inbound from payments-service (topic {@code payments.payment-returned}) — bounced/reversed payment. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentReturnedPayload(
        String eventId,
        UUID creditAccountId,
        BigDecimal amount
) {}
