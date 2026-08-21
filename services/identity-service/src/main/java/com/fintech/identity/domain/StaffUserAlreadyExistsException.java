package com.fintech.identity.domain;

import com.fintech.shared.exception.DomainException;

public class StaffUserAlreadyExistsException extends DomainException {

    public StaffUserAlreadyExistsException(String email) {
        super("AUTH_STAFF_ALREADY_EXISTS", "Staff user already exists: " + email);
    }
}
