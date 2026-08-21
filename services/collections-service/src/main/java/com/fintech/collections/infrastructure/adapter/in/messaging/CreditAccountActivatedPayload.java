package com.fintech.collections.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Inbound from credit-portfolio (topic {@code credit-portfolio.credit-account-activated}). */
@JsonIgnoreProperties(ignoreUnknown = true)
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
