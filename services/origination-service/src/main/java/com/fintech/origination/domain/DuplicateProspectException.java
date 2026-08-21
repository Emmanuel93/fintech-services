package com.fintech.origination.domain;

import com.fintech.shared.exception.DomainException;

public class DuplicateProspectException extends DomainException {

    public DuplicateProspectException(String field) {
        super("DUPLICATE_PROSPECT", "A prospect already exists with the provided " + field);
    }
}
