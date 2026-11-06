package com.fintech.creditportfolio.domain;

import java.util.Set;

public enum CreditAccountStatus {
    PENDING_ACTIVATION,
    ACTIVE,
    SUSPENDED,
    RESTRUCTURED,
    SETTLED,
    WRITTEN_OFF,
    CLOSED,
    /** Its only disbursement could not happen: it never lent anything, and every movement was compensated (D63). */
    CANCELLED;

    private static final Set<CreditAccountStatus> TERMINAL =
            Set.of(SETTLED, WRITTEN_OFF, CLOSED, CANCELLED);

    public boolean isTerminal() { return TERMINAL.contains(this); }
}
