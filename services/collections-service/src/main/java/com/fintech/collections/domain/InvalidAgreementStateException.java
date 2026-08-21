package com.fintech.collections.domain;

import com.fintech.shared.exception.DomainException;

public class InvalidAgreementStateException extends DomainException {
    public InvalidAgreementStateException(String message) {
        super("COLLECTIONS_INVALID_AGREEMENT_STATE", message);
    }
}
