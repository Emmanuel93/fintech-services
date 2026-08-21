package com.fintech.beneficiary.domain;

import com.fintech.shared.exception.DomainException;

/** Un dato de la colocación viola una invariante del dominio. */
public class PlacementValidationException extends DomainException {
    public PlacementValidationException(String detail) {
        super("BENEFICIARY_PLACEMENT_INVALID", detail);
    }
}
