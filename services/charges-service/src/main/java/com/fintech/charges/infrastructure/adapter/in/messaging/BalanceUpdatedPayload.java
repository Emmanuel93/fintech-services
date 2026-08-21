package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * ACL projection of credit-portfolio's BalanceUpdatedEvent
 * (topic {@code credit-portfolio.balance-updated}).
 * Carries the full balance breakdown used to maintain AccountBalanceSnapshot
 * and keep principalBalance in sync for daily accrual jobs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BalanceUpdatedPayload(
        String eventId,
        UUID creditAccountId,
        UUID obligorPartyId,
        BigDecimal principalBalance,
        BigDecimal accruedInterestBalance,
        BigDecimal penaltyBalance,
        BigDecimal availableCredit,
        BigDecimal totalDebt,
        BigDecimal creditLimit,
        long balanceVersion,
        String accountStatus
) {}
