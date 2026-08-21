package com.fintech.commission.domain;

public enum CommissionRecordStatus {
    ACCRUED, LIQUIDATED, REVERSED;

    public boolean isTerminalForEditing() { return this == LIQUIDATED || this == REVERSED; }
}
