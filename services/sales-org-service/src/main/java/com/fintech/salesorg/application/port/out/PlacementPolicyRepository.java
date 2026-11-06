package com.fintech.salesorg.application.port.out;

import com.fintech.salesorg.domain.UnitPlacementRule;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The rows that make up the declared placement policies of the units. */
public interface PlacementPolicyRepository {

    List<UnitPlacementRule> findByUnitId(UUID unitId);

    List<UnitPlacementRule> findByUnitIds(Collection<UUID> unitIds);

    void saveAll(Collection<UnitPlacementRule> rules);

    void deleteAll(Collection<UnitPlacementRule> rules);
}
