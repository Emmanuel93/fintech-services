package com.fintech.collections.domain;

import com.fintech.shared.exception.DomainException;

public class CollectionCaseNotFoundException extends DomainException {
    public CollectionCaseNotFoundException(String id) {
        super("COLLECTIONS_CASE_NOT_FOUND", "CollectionCase not found: " + id);
    }
}
