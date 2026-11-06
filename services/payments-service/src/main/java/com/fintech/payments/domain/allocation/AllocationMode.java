package com.fintech.payments.domain.allocation;

/**
 * How a peso of a received credit was applied. REFERENCED: the transfer's numeric reference named the loan. BY_RULE:
 * no usable reference, so seniority decided (the oldest loan with debt first). CREDIT_BALANCE: no loan owed it when it
 * arrived; it stays as the payer's balance in favor until a due installment uses it or it is refunded.
 */
public enum AllocationMode {
    REFERENCED, BY_RULE, CREDIT_BALANCE
}
