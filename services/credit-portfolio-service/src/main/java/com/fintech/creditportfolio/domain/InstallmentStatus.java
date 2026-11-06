package com.fintech.creditportfolio.domain;

public enum InstallmentStatus {
    PENDING, PAID, OVERDUE, PARTIAL,
    /** Superseded by a restructure: kept for the record, owes nothing. */
    REPLACED,
    /** Its credit was cancelled because the money never reached the borrower: kept for the record, owes nothing. */
    CANCELLED;

    /** A plan entry that owes nothing and is never billed, reminded or accrued: replaced or cancelled. */
    public boolean isVoid() {
        return this == REPLACED || this == CANCELLED;
    }
}
