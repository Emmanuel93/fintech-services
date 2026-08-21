package com.fintech.stp.domain;

import com.fintech.shared.exception.DomainException;

/** Transición no permitida sobre la orden. */
public class InvalidStpOrderStateException extends DomainException {

    public InvalidStpOrderStateException(String message) {
        super("STP_INVALID_ORDER_STATE", message);
    }
}
