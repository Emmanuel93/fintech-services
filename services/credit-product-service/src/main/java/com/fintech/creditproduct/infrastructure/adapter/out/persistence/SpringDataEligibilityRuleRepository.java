package com.fintech.creditproduct.infrastructure.adapter.out.persistence;

import com.fintech.creditproduct.domain.EligibilityRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface SpringDataEligibilityRuleRepository extends JpaRepository<EligibilityRule, UUID> {

    List<EligibilityRule> findByProductDefinitionId(UUID productDefinitionId);

    @Modifying
    @Query("DELETE FROM EligibilityRule e WHERE e.productDefinitionId = :productDefinitionId")
    void deleteByProductDefinitionId(UUID productDefinitionId);
}
