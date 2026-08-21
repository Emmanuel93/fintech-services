package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Inbound from charges-service (topic {@code charges.charge-reversed}).
 * Covers both ChargeReversed (technical correction) and ChargeWaived (forgiveness) —
 * both reverse the original charge from its bucket; {@code waived} distinguishes for T4/accounting.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChargeReversedPayload(
        String eventId,
        UUID creditAccountId,
        String originalChargeType,
        BigDecimal amount,
        boolean waived
) {}
