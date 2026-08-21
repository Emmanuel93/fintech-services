package com.fintech.wallet.infrastructure.adapter.in.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreditAccountActivatedPayload(
        String eventId,
        Instant occurredOn,
        UUID creditAccountId,
        UUID contractId,
        UUID obligorPartyId,
        String productType,
        String productBehavior,
        BigDecimal nominalRate,
        BigDecimal moratoriumRate,
        BigDecimal openingFeeRate,
        BigDecimal principalBalance,
        BigDecimal creditLimit,
        String riskTier,
        Instant activatedAt
) {}
