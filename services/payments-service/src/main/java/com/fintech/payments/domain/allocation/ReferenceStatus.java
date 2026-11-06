package com.fintech.payments.domain.allocation;

/** Whether the transfer's numeric reference named one of the payer's loans. */
public enum ReferenceStatus {
    /** It named a loan of the payer: that loan was paid first. */
    MATCHED,
    /** It came, but named no loan of the payer (a typo, another person's loan): applied by rule. */
    NOT_FOUND,
    /** The transfer carried no reference: applied by rule. */
    ABSENT
}
