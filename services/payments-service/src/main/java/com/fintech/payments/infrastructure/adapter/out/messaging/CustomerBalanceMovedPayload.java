package com.fintech.payments.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Outbound {@code payments.customer-balance-moved}: one change of a payer's balance in favor, with its cause. */
public record CustomerBalanceMovedPayload(
        String eventId,
        UUID movementId,
        UUID partyId,
        String movementType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        UUID inboundCreditId,
        UUID paymentOrderId,
        UUID creditAccountId,
        LocalDate businessDate,
        Instant occurredOn
) {}
