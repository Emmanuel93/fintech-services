package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * ACL projection of credit-portfolio's ChargeRejectedEvent
 * (topic {@code credit-portfolio.charge-rejected}).
 * Consumed to reverse the ChargeRecord that was optimistically persisted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChargeRejectedPayload(
        String eventId,
        String sourceEventId,
        UUID creditAccountId,
        String chargeType,
        BigDecimal rejectedAmount,
        String reason
) {}
