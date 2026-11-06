package com.fintech.salesorg.application;

import com.fintech.salesorg.domain.PlacementPolicy;

import java.util.UUID;

/**
 * A unit's placement policy once inheritance is resolved: what it declares, what applies, and where it came from.
 *
 * @param inheritedFrom the code of the deepest unit that declared something on the way down (the unit itself when it
 *                      declares); {@code null} when nothing is configured above or at the unit
 */
public record EffectivePlacementPolicy(UUID unitId, String unitCode, String path, String levelCode, UUID partyRef,
                                       PlacementPolicy declared, PlacementPolicy effective, String inheritedFrom) {

    public boolean configured() {
        return effective != null && !effective.isEmpty();
    }
}
