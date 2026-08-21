package com.fintech.wallet.infrastructure.adapter.in.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DispositionCompletedPayload(
        UUID dispositionId,
        UUID creditAccountId,
        UUID obligorPartyId,
        BigDecimal amount,
        String dispositionType,
        BigDecimal availableCredit,
        long balanceVersion,
        Instant occurredAt
) {}
