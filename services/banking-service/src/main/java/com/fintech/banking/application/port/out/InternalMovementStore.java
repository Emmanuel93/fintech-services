package com.fintech.banking.application.port.out;

import com.fintech.banking.domain.InternalMovement;

/** The treasury's cash book: every movement of money the platform says happened, once. */
public interface InternalMovementStore {

    /** Saves it unless the fact that brought it was already projected; returns the movement kept (new or earlier). */
    InternalMovement save(InternalMovement movement);

    /** Whether the fact {@code sourceEventId} brought money into the cash book. */
    boolean contains(String sourceEventId);
}
