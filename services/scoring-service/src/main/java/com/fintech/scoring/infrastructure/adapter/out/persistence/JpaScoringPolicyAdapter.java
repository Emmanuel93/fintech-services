package com.fintech.scoring.infrastructure.adapter.out.persistence;

import com.fintech.scoring.application.port.out.ScoringPolicyRepository;
import com.fintech.scoring.domain.ScoringPolicy;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaScoringPolicyAdapter implements ScoringPolicyRepository {

    private final SpringDataScoringPolicyRepository jpa;

    JpaScoringPolicyAdapter(SpringDataScoringPolicyRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ScoringPolicy save(ScoringPolicy policy) {
        return jpa.save(policy);
    }

    @Override
    public void flush() {
        jpa.flush();
    }

    @Override
    public Optional<ScoringPolicy> findById(UUID policyId) {
        return jpa.findById(policyId);
    }

    @Override
    public Optional<ScoringPolicy> findActiveBy(String productTypeIntent) {
        return jpa.findByProductTypeIntentAndActiveTrue(productTypeIntent);
    }

    @Override
    public List<ScoringPolicy> findAllActive() {
        return jpa.findAllByActiveTrue();
    }
}
