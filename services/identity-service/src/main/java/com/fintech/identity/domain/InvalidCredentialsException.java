package com.fintech.identity.domain;

import com.fintech.shared.exception.DomainException;

public class InvalidCredentialsException extends DomainException {

    public InvalidCredentialsException() {
        super("AUTH_INVALID_CREDENTIALS", "Invalid credentials");
    }
}
