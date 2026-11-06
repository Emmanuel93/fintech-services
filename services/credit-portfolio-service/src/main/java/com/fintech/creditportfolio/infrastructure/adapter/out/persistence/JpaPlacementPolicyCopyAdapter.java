package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.PlacementPolicyCopyRepository;
import com.fintech.creditportfolio.domain.beneficiary.PlacementPolicyCopy;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class JpaPlacementPolicyCopyAdapter implements PlacementPolicyCopyRepository {

    private final SpringDataPlacementPolicyCopyRepository jpa;

    JpaPlacementPolicyCopyAdapter(SpringDataPlacementPolicyCopyRepository jpa) {
        this.jpa = jpa;
    }

    @Override public PlacementPolicyCopy save(PlacementPolicyCopy copy)              { return jpa.save(copy); }
    @Override public Optional<PlacementPolicyCopy> findByUnitCode(String unitCode)   { return jpa.findById(unitCode); }
    @Override public Optional<PlacementPolicyCopy> findByPartyRef(UUID partyRef)     { return jpa.findFirstByPartyRefOrderByUpdatedAtDesc(partyRef); }
}
