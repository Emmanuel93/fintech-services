package com.fintech.identity.domain;

import com.fintech.shared.exception.DomainException;

public class InvalidMfaCodeException extends DomainException {

    public InvalidMfaCodeException() {
        super("AUTH_INVALID_MFA_CODE", "Invalid or expired MFA code");
    }
}
