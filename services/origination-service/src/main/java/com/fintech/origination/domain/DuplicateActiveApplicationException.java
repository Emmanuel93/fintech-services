package com.fintech.origination.domain;

import com.fintech.shared.exception.DomainException;

/**
 * OA-03: only one active credit application is allowed per (prospectId, productType).
 */
public class DuplicateActiveApplicationException extends DomainException {

    public DuplicateActiveApplicationException(ProductType productType) {
        super("DUPLICATE_ACTIVE_APPLICATION",
              "An active credit application already exists for product " + productType);
    }
}
