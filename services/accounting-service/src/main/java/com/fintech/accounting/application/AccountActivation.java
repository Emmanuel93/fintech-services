package com.fintech.accounting.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A loan's activation, as accounting books it: the line authorized (memorandum accounts), the principal placed when it
 * is disbursed at activation, and what every later voucher of the loan inherits from it — its branch, its product and
 * its life (what an opening fee is deferred over).
 *
 * @param authorizedAmount the line authorized: the limit of a revolving line, the principal of a simple loan
 * @param disbursedAmount  the principal placed at activation; {@code null} or zero when it is disposed of later
 * @param productType      the product, as credit-portfolio published it
 * @param termMonths       the loan's life in months, when it has one
 */
public record AccountActivation(
        String sourceEventId,
        UUID creditAccountId,
        UUID obligorPartyId,
        BigDecimal authorizedAmount,
        BigDecimal disbursedAmount,
        String orgUnitCode,
        String productType,
        Instant occurredAt,
        Integer termMonths
) {}
