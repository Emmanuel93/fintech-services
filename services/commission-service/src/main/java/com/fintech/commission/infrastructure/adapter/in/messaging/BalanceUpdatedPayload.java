package com.fintech.commission.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/** Inbound {@code credit-portfolio.balance-updated} — solo nos importa accruedInterestBalance. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BalanceUpdatedPayload(
        String eventId,
        UUID creditAccountId,
        BigDecimal accruedInterestBalance,
        String triggerEvent,
        long balanceVersion
) {}
