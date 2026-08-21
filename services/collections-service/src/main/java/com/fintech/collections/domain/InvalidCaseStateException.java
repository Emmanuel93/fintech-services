package com.fintech.collections.domain;

import com.fintech.shared.exception.DomainException;

public class InvalidCaseStateException extends DomainException {
    public InvalidCaseStateException(String message) {
        super("COLLECTIONS_INVALID_CASE_STATE", message);
    }
}
