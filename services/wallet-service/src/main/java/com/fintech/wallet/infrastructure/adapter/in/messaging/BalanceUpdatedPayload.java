package com.fintech.wallet.infrastructure.adapter.in.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BalanceUpdatedPayload(
        String eventId,
        Instant occurredOn,
        UUID creditAccountId,
        UUID obligorPartyId,
        BigDecimal principalBalance,
        BigDecimal accruedInterestBalance,
        BigDecimal penaltyBalance,
        BigDecimal availableCredit,
        BigDecimal totalDebt,
        String triggerEvent,
        String accountStatus,
        long balanceVersion
) {}
