package com.fintech.salesorg.domain;

import com.fintech.shared.exception.DomainException;

public class DuplicateCodeException extends DomainException {
    public DuplicateCodeException(String code) {
        super("DUPLICATE_CODE", "Code already in use: " + code);
    }
}
