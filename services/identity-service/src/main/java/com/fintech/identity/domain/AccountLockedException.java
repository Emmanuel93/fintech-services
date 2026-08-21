package com.fintech.identity.domain;

import com.fintech.shared.exception.DomainException;
import java.time.Instant;

public class AccountLockedException extends DomainException {

    private final Instant lockedUntil;

    public AccountLockedException(Instant lockedUntil) {
        super("AUTH_ACCOUNT_LOCKED",
              "Account is locked until " + lockedUntil);
        this.lockedUntil = lockedUntil;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }
}
