package com.fintech.origination.domain;

import com.fintech.shared.exception.DomainException;

public class CreditApplicationNotFoundException extends DomainException {

    public CreditApplicationNotFoundException(String applicationId) {
        super("CREDIT_APPLICATION_NOT_FOUND", "Credit application not found: " + applicationId);
    }
}
