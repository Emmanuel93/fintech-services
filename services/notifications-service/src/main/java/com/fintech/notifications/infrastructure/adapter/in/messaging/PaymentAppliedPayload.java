package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/** Inbound {@code payments.payment-applied} — no trae installmentId/Number, ver NT-11. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentAppliedPayload(
        String eventId,
        UUID creditAccountId,
        BigDecimal amount
) {}
