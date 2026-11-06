package com.fintech.salesorg.domain;

import com.fintech.shared.exception.DomainException;

/** A placement policy that does not fit the product, widens what the unit inherits, or contradicts itself. */
public class InvalidPlacementPolicyException extends DomainException {

    public InvalidPlacementPolicyException(String message) {
        super("INVALID_PLACEMENT_POLICY", message);
    }
}
