package com.fintech.collections.domain;

public enum AgreementStatus {
    PROPOSED, ACCEPTED, EXECUTED, REJECTED, EXPIRED;

    public boolean isTerminal() {
        return this == EXECUTED || this == REJECTED || this == EXPIRED;
    }
}
