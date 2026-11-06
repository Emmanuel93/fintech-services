package com.fintech.creditportfolio.application.port.out;

import com.fintech.creditportfolio.domain.beneficiary.PlacementPolicyCopy;

import java.util.Optional;
import java.util.UUID;

/** The portfolio's copy of the placement policies sales-org publishes, one per unit. */
public interface PlacementPolicyCopyRepository {

    PlacementPolicyCopy save(PlacementPolicyCopy copy);

    Optional<PlacementPolicyCopy> findByUnitCode(String unitCode);

    /** The DISTRIBUTOR node of a party: her own copy, with her stricter policy folded in. */
    Optional<PlacementPolicyCopy> findByPartyRef(UUID partyRef);
}
