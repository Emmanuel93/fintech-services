package com.fintech.creditproduct.application.port.out;

import com.fintech.creditproduct.domain.EligibilityRule;

import java.util.List;
import java.util.UUID;

public interface EligibilityRuleRepository {
    EligibilityRule save(EligibilityRule rule);
    List<EligibilityRule> saveAll(Iterable<EligibilityRule> rules);
    List<EligibilityRule> findByProductDefinitionId(UUID productDefinitionId);
    void deleteByProductDefinitionId(UUID productDefinitionId);
}
