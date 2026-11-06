package com.fintech.commission.domain;

public enum CommissionRecordStatus {
    ACCRUED, LIQUIDATED, REVERSED,
    /** Settled against the distributor's own statement, netted from what she delivered: never in a lot. */
    COMPENSATED;

    public boolean isTerminalForEditing() { return this == LIQUIDATED || this == REVERSED || this == COMPENSATED; }
}
