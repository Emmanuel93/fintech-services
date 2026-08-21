package com.fintech.creditportfolio.domain;

import java.util.Set;

public enum CreditAccountStatus {
    PENDING_ACTIVATION,
    ACTIVE,
    SUSPENDED,
    RESTRUCTURED,
    SETTLED,
    WRITTEN_OFF,
    CLOSED;

    private static final Set<CreditAccountStatus> TERMINAL =
            Set.of(SETTLED, WRITTEN_OFF, CLOSED);

    public boolean isTerminal() { return TERMINAL.contains(this); }
}
