package com.fintech.payments.domain;

/**
 * Determines what happens when the customer submits more than their totalDebt.
 * Configured globally via PaymentsProperties (overridable via T5).
 */
public enum OverpaymentStrategy {
    /** Excess is returned to payer via payment-returned event (SPEI devolución). */
    RETURN_TO_PAYER,
    /** Full amount forwarded to credit-portfolio; excess pre-pays the next installment. */
    APPLY_NEXT_INSTALLMENT
}
