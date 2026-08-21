package com.fintech.charges.domain;

import com.fintech.shared.exception.DomainException;

public class DuplicateOpeningFeeException extends DomainException {
    public DuplicateOpeningFeeException(String creditAccountId) {
        super("CHARGES_DUPLICATE_OPENING_FEE",
                "OPENING_FEE already exists for creditAccountId: " + creditAccountId);
    }
}
