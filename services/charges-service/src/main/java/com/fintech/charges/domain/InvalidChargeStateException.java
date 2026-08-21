package com.fintech.charges.domain;

import com.fintech.shared.exception.DomainException;

public class InvalidChargeStateException extends DomainException {
    public InvalidChargeStateException(String message) {
        super("CHARGES_INVALID_STATE", message);
    }
}
