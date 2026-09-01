package com.fintech.closing.domain;

public enum UnitStatus {
    PENDING,
    /** Tomada por un pod, con arrendamiento vivo. */
    CLAIMED,
    DONE,
    FAILED,
    /** La política del producto la excluye, o la cuenta ya no aplica. No es un error. */
    SKIPPED
}
