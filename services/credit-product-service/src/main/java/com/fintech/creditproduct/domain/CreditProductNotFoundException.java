package com.fintech.creditproduct.domain;

import com.fintech.shared.exception.DomainException;

public class CreditProductNotFoundException extends DomainException {

    public CreditProductNotFoundException(String identifier) {
        super("CREDIT_PRODUCT_NOT_FOUND", "Credit product not found: " + identifier);
    }
}
