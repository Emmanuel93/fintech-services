package com.fintech.creditproduct.domain;

import com.fintech.shared.exception.DomainException;

import java.util.UUID;

public class InvalidProductTransitionException extends DomainException {

    public InvalidProductTransitionException(UUID productDefinitionId, ProductStatus current, ProductStatus target) {
        super(
            "INVALID_PRODUCT_TRANSITION",
            "Cannot transition product %s from %s to %s".formatted(productDefinitionId, current, target)
        );
    }
}
