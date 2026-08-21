package com.fintech.collections.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/** Inbound from payments (topic {@code payments.payment-applied}). eventId is the paymentOrderId. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentAppliedPayload(
        String eventId,
        UUID creditAccountId,
        BigDecimal amount,
        long snapshotVersion
) {}
