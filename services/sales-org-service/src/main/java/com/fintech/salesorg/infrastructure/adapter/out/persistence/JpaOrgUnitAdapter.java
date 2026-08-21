package com.fintech.salesorg.infrastructure.adapter.out.persistence;

import com.fintech.salesorg.application.port.out.OrgUnitRepository;
import com.fintech.salesorg.domain.OrgUnit;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaOrgUnitAdapter implements OrgUnitRepository {

    @Override
    public java.math.BigDecimal findEffectiveVatRate(String code) {
        return jpa.findEffectiveVatRate(code);
    }

    private final SpringDataOrgUnitRepository jpa;

    JpaOrgUnitAdapter(SpringDataOrgUnitRepository jpa) {
        this.jpa = jpa;
    }

    @Override public OrgUnit save(OrgUnit unit)               { return jpa.save(unit); }
    @Override public Optional<OrgUnit> findById(UUID unitId)  { return jpa.findById(unitId); }
    @Override public Optional<OrgUnit> findByCode(String code){ return jpa.findByCode(code); }
    @Override public boolean existsByCode(String code)        { return jpa.existsByCode(code); }
    @Override public boolean existsByLevelId(UUID levelId)    { return jpa.existsByLevelId(levelId); }
    @Override public List<OrgUnit> findAll()                  { return jpa.findAll(); }

    @Override public List<OrgUnit> findByPartyRef(UUID partyRef) { return jpa.findByPartyRef(partyRef); }

    @Override
    public List<OrgUnit> findChildren(UUID parentUnitId) {
        return jpa.findByParentUnitIdOrderByCodeAsc(parentUnitId);
    }

    @Override
    public List<OrgUnit> findSubtree(String ancestorPath) {
        return jpa.findSubtree(ancestorPath);
    }

    @Override
    public List<UUID> findPartyRefsInSubtreeByLevel(String ancestorPath, String levelCode) {
        return jpa.findPartyRefsInSubtreeByLevel(ancestorPath, levelCode);
    }

    @Override
    public java.util.Optional<UUID> findPartyRefByCodeAndLevel(String code, String levelCode) {
        return jpa.findPartyRefByCodeAndLevel(code, levelCode);
    }
}
