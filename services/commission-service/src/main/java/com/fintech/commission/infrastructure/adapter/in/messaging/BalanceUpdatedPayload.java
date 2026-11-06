package com.fintech.commission.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Inbound {@code credit-portfolio.balance-updated}: the interest balance (the legacy measure), the fact's breakdown
 * ({@code interestDelta}), the payment order it comes from ({@code sourceEventId}) and its portfolio day.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BalanceUpdatedPayload(
        String eventId,
        UUID creditAccountId,
        BigDecimal accruedInterestBalance,
        String triggerEvent,
        long balanceVersion,
        BigDecimal interestDelta,
        String sourceEventId,
        LocalDate valueDate,
        /** How the product settles the distributor's commission ({@code NETTED} or {@code PAYOUT}); null when it pays none. */
        String commissionSettlement,
        /** On a {@code COMMISSION_NETTED} (or its reversal): the commission the portfolio settled against a statement. */
        NettingPayload commission
) {
    /** The portfolio's {@code CommissionNetting}: the statement, the tier and the amount. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NettingPayload(int cycleNumber, LocalDate paymentDueDate, long daysLate, BigDecimal rate,
                                 BigDecimal onTimeRate, BigDecimal basis, BigDecimal amount, String paymentOrder) {}
}
