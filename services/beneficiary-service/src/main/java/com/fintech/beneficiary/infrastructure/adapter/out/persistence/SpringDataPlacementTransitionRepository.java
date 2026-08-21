package com.fintech.beneficiary.infrastructure.adapter.out.persistence;

import com.fintech.beneficiary.application.port.out.PlacementTransitionRepository;
import com.fintech.beneficiary.domain.PlacementTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataPlacementTransitionRepository
        extends JpaRepository<PlacementTransition, UUID>, PlacementTransitionRepository {

    @Override
    List<PlacementTransition> findByPlacementIdOrderByOccurredAtAsc(UUID placementId);
}
