package com.fintech.stp.domain.collection;

/** ACTIVE receives credits; BLOCKED returns them (cause 2); CLOSED returns them (cause 3) and is never reused. */
public enum CollectionAccountStatus {
    ACTIVE, BLOCKED, CLOSED
}
