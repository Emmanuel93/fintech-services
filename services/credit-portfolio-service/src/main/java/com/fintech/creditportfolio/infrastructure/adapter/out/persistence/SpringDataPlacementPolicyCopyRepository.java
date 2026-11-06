package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.domain.beneficiary.PlacementPolicyCopy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataPlacementPolicyCopyRepository extends JpaRepository<PlacementPolicyCopy, String> {

    Optional<PlacementPolicyCopy> findFirstByPartyRefOrderByUpdatedAtDesc(UUID partyRef);
}
