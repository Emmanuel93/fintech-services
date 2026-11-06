package com.fintech.salesorg.infrastructure.adapter.out.persistence;

import com.fintech.salesorg.domain.UnitPlacementRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface SpringDataUnitPlacementRuleRepository extends JpaRepository<UnitPlacementRule, UUID> {

    List<UnitPlacementRule> findByUnitId(UUID unitId);

    List<UnitPlacementRule> findByUnitIdIn(Collection<UUID> unitIds);
}
