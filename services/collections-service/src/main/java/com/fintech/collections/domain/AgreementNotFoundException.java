package com.fintech.collections.domain;

import com.fintech.shared.exception.DomainException;

public class AgreementNotFoundException extends DomainException {
    public AgreementNotFoundException(String id) {
        super("COLLECTIONS_AGREEMENT_NOT_FOUND", "CollectionAgreement not found: " + id);
    }
}
