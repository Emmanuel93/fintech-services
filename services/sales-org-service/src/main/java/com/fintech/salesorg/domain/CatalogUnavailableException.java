package com.fintech.salesorg.domain;

import com.fintech.shared.exception.DomainException;

/**
 * The product catalog could not be read, so a placement policy cannot be validated against the line's product.
 * Saving it unchecked would let a branch allow placements the product refuses; the write is refused instead.
 */
public class CatalogUnavailableException extends DomainException {

    public CatalogUnavailableException(String message, Throwable cause) {
        super("CATALOG_UNAVAILABLE", message, cause);
    }
}
