package com.fintech.beneficiary.domain;

import com.fintech.shared.exception.DomainException;

import java.util.UUID;

/** No existe colocación con ese id. */
public class PlacementNotFoundException extends DomainException {
    public PlacementNotFoundException(UUID placementId) {
        super("BENEFICIARY_PLACEMENT_NOT_FOUND", "No existe la colocación " + placementId);
    }
}
