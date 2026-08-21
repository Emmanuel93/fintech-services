package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/** Inbound from payments-service (topic {@code payments.payment-applied}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentAppliedPayload(
        String eventId,
        UUID creditAccountId,
        BigDecimal amount
) {}
