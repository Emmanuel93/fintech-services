package com.fintech.banking.application.port.out;

import com.fintech.banking.domain.InternalMovement;

/** Announces a movement of the cash book once it is committed: accounting reconciles 1101 against the book. */
public interface CashMovementPublisher {

    void recorded(InternalMovement movement);
}
