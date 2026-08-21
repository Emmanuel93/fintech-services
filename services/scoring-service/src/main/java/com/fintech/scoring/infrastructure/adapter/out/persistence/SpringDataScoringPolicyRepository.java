package com.fintech.scoring.infrastructure.adapter.out.persistence;

import com.fintech.scoring.domain.ScoringPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataScoringPolicyRepository extends JpaRepository<ScoringPolicy, UUID> {
    Optional<ScoringPolicy> findByProductTypeIntentAndActiveTrue(String productTypeIntent);
    List<ScoringPolicy> findAllByActiveTrue();
}
