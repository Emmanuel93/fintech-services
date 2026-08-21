package com.fintech.payments.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/** ACL projection of credit-portfolio.payment-rejected. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentRejectedPayload(
        String eventId,
        String sourceEventId,
        UUID creditAccountId,
        BigDecimal rejectedAmount,
        String reason
) {}
