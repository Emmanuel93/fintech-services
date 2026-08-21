package com.fintech.identity.domain;

import com.fintech.shared.exception.DomainException;

public class InvalidStaffStateException extends DomainException {

    public InvalidStaffStateException(String message) {
        super("AUTH_STAFF_INVALID_STATE", message);
    }
}
