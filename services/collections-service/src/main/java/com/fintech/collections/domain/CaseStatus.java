package com.fintech.collections.domain;

public enum CaseStatus {
    OPEN, MANAGED, LEGAL, WRITTEN_OFF, CLOSED;

    public boolean isTerminal() {
        return this == WRITTEN_OFF || this == CLOSED;
    }
}
