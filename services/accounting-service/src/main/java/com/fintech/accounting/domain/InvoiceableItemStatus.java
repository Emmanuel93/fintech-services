package com.fintech.accounting.domain;

public enum InvoiceableItemStatus {
    PENDING, BILLED,
    /** Its income was reversed before it was invoiced: a credit cancelled because its disbursement failed (D63). */
    CANCELLED
}
