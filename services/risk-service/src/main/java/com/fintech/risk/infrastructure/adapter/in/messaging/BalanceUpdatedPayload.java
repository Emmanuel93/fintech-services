package com.fintech.risk.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/** Inbound from credit-portfolio ({@code credit-portfolio.balance-updated}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BalanceUpdatedPayload(
        UUID creditAccountId,
        UUID obligorPartyId,
        BigDecimal totalDebt,
        String accountStatus,
        /** Available credit on a revolving line: the undrawn exposure. Null on loans and older producers. */
        BigDecimal availableCredit
) {}
