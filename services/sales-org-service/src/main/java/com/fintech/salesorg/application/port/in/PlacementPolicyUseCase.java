package com.fintech.salesorg.application.port.in;

import com.fintech.salesorg.application.EffectivePlacementPolicy;
import com.fintech.salesorg.domain.OrgUnit;
import com.fintech.salesorg.domain.PlacementPolicy;

/**
 * The placement policy of the distributor line, unit by unit.
 *
 * <p>Declared at a branch (or above) and inherited below; a distributor may declare her own, only stricter. Every
 * change is published, resolved, for the whole subtree it touches, so the portfolio keeps a copy of what applies to
 * each unit and each distributor without asking.
 */
public interface PlacementPolicyUseCase {

    /** Declares (or replaces) the unit's own policy; an empty one clears it. Validated against the line's product. */
    EffectivePlacementPolicy declare(String unitCode, PlacementPolicy policy, String staffUserId);

    /** The unit goes back to inheriting. */
    EffectivePlacementPolicy clear(String unitCode, String staffUserId);

    /** What the unit declares itself (empty when it only inherits). */
    PlacementPolicy declared(String unitCode);

    /** What applies at the unit once inheritance is resolved. */
    EffectivePlacementPolicy effective(String unitCode);

    /** Publishes the resolved policy of a unit and everything under it (after a unit is created or moved). */
    void republish(OrgUnit unit);
}
