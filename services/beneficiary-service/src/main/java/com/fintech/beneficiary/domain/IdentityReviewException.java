package com.fintech.beneficiary.domain;

import com.fintech.shared.exception.DomainException;

/** El dictamen de identidad que se intenta registrar no es válido. */
public class IdentityReviewException extends DomainException {
    public IdentityReviewException(String detail) {
        super("BENEFICIARY_INVALID_IDENTITY_REVIEW", detail);
    }
}
