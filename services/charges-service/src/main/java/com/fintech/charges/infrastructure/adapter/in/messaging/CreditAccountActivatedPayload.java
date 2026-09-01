package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * ACL projection of credit-portfolio's CreditAccountActivatedEvent
 * (topic {@code credit-portfolio.credit-account-activated}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditAccountActivatedPayload(
        String eventId,
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
        Instant activatedAt,
        /** BNPL: desde cuándo devenga. Nulo = desde el alta (BK-28). */
        java.time.LocalDate accrualStartDate) {}
