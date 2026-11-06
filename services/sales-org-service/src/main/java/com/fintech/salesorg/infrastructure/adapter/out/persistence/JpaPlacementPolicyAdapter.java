package com.fintech.salesorg.infrastructure.adapter.out.persistence;

import com.fintech.salesorg.application.port.out.PlacementPolicyRepository;
import com.fintech.salesorg.domain.UnitPlacementRule;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
class JpaPlacementPolicyAdapter implements PlacementPolicyRepository {

    private final SpringDataUnitPlacementRuleRepository jpa;

    JpaPlacementPolicyAdapter(SpringDataUnitPlacementRuleRepository jpa) {
        this.jpa = jpa;
    }

    @Override public List<UnitPlacementRule> findByUnitId(UUID unitId)                 { return jpa.findByUnitId(unitId); }
    @Override public List<UnitPlacementRule> findByUnitIds(Collection<UUID> unitIds)   { return unitIds.isEmpty() ? List.of() : jpa.findByUnitIdIn(unitIds); }
    @Override public void saveAll(Collection<UnitPlacementRule> rules)                 { jpa.saveAll(rules); }
    @Override public void deleteAll(Collection<UnitPlacementRule> rules)               { jpa.deleteAll(rules); }
}
