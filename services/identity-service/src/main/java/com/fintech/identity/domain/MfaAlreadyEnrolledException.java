package com.fintech.identity.domain;

import com.fintech.shared.exception.DomainException;

public class MfaAlreadyEnrolledException extends DomainException {

    public MfaAlreadyEnrolledException() {
        super("AUTH_MFA_ALREADY_ENROLLED", "MFA is already enrolled and active for this account");
    }
}
