package com.fintech.identity.domain;

import com.fintech.shared.exception.DomainException;

public class StaffUserNotFoundException extends DomainException {

    public StaffUserNotFoundException(String identifier) {
        super("AUTH_STAFF_NOT_FOUND", "Staff user not found: " + identifier);
    }
}
