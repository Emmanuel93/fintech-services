package com.fintech.commission.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Outbound {@code commission.commission-accrued}: the commission (before IVA) and its IVA, owed to the beneficiary for
 * the product, from the payment order {@code paymentRef}. {@code valueDate}: the portfolio's day of the payment;
 * {@code occurredOn}: that day's noon in the business zone, the voucher's date ({@code accrualDate} is when this service
 * read the payment).
 */
public record CommissionAccruedPayload(
        UUID commissionId,
        String commissionType,
        UUID creditAccountId,
        UUID beneficiaryPartyId,
        String productType,
        String paymentRef,
        BigDecimal basis,
        BigDecimal rate,
        BigDecimal amount,
        BigDecimal ivaAmount,
        String period,
        LocalDate valueDate,
        Instant occurredOn,
        Instant accrualDate,
        /** NETTED when settled against the distributor's statement; null when paid in a lot. */
        String settlement
) {}
