package com.fintech.accounting.application.service;

import com.fintech.accounting.application.CloseReport;

/** A month that does not reconcile stays open: the report says which checks are off and by how much. */
public class PeriodNotReconciledException extends RuntimeException {

    private final transient CloseReport report;

    public PeriodNotReconciledException(CloseReport report) {
        super(report.summary());
        this.report = report;
    }

    public CloseReport report() {
        return report;
    }
}
