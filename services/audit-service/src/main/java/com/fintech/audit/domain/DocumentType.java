package com.fintech.audit.domain;

public enum DocumentType {
    BUREAU_REPORT(5),
    CONTRACT(10),
    ACCOUNT_STATEMENT(5),
    AML_RECORD(10),
    REJECTION_RECORD(5),
    WRITE_OFF_RECORD(10),
    CHARGE_JUSTIFICATION(5),
    KYC_DOCUMENT(7);

    private final int retentionYears;

    DocumentType(int retentionYears) {
        this.retentionYears = retentionYears;
    }

    public int retentionYears() {
        return retentionYears;
    }
}
