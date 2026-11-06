package com.fintech.commission.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Where a commission comes from: the loan, who it is paid to, the loan's product (a lot pays one beneficiary for one
 * product), the fact that produced it, the payment order (what a return names to undo it) and the portfolio's date of
 * the payment, which decides the commission's period.
 */
public record CommissionSource(UUID creditAccountId, UUID beneficiaryPartyId, String productType,
                               String sourceEventId, String paymentRef, LocalDate valueDate) {}
