package com.fintech.identity.domain;

import com.fintech.shared.exception.DomainException;

public class MfaNotEnrolledException extends DomainException {

    public MfaNotEnrolledException() {
        super("AUTH_MFA_NOT_ENROLLED", "MFA is not enrolled for this account");
    }
}
