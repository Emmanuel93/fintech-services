package com.fintech.beneficiary.application.port.out;

import com.fintech.beneficiary.domain.PlacementTransition;

import java.util.List;
import java.util.UUID;

public interface PlacementTransitionRepository {

    PlacementTransition save(PlacementTransition transition);

    List<PlacementTransition> findByPlacementIdOrderByOccurredAtAsc(UUID placementId);
}
