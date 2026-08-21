package com.fintech.creditproduct.infrastructure.adapter.out.persistence;

import com.fintech.creditproduct.application.port.out.EligibilityRuleRepository;
import com.fintech.creditproduct.domain.EligibilityRule;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class JpaEligibilityRuleAdapter implements EligibilityRuleRepository {

    private final SpringDataEligibilityRuleRepository repository;

    public JpaEligibilityRuleAdapter(SpringDataEligibilityRuleRepository repository) {
        this.repository = repository;
    }

    @Override
    public EligibilityRule save(EligibilityRule rule) {
        return repository.save(rule);
    }

    @Override
    public List<EligibilityRule> saveAll(Iterable<EligibilityRule> rules) {
        return repository.saveAll(rules);
    }

    @Override
    public List<EligibilityRule> findByProductDefinitionId(UUID productDefinitionId) {
        return repository.findByProductDefinitionId(productDefinitionId);
    }

    @Override
    public void deleteByProductDefinitionId(UUID productDefinitionId) {
        repository.deleteByProductDefinitionId(productDefinitionId);
    }
}
