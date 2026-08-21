package com.fintech.risk.application.service;

import com.fintech.risk.application.CreateProvisionPolicyCommand;
import com.fintech.risk.application.port.in.ProvisionPolicyUseCase;
import com.fintech.risk.application.port.out.ProvisionPolicyRepository;
import com.fintech.risk.domain.ProvisionPolicy;
import com.fintech.risk.domain.ProvisionPolicyNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Versioned provision-rate policies — one ACTIVE per productType (PP-01), same maker pattern as
 * ScoringPolicy (D2). Creating a new policy deprecates the prior ACTIVE one and bumps the version.
 */
@Service
@Transactional
public class ProvisionPolicyService implements ProvisionPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProvisionPolicyService.class);

    private final ProvisionPolicyRepository repository;

    public ProvisionPolicyService(ProvisionPolicyRepository repository) {
        this.repository = repository;
    }

    @Override
    public ProvisionPolicy create(CreateProvisionPolicyCommand cmd) {
        // Deprecate the current ACTIVE policy so the partial unique index (one ACTIVE per productType) holds
        repository.findActiveByProductType(cmd.productType()).ifPresent(existing -> {
            existing.deprecate();
            repository.save(existing);
        });

        int nextVersion = repository.maxVersionForProductType(cmd.productType()) + 1;
        ProvisionPolicy policy = ProvisionPolicy.create(cmd.productType(), nextVersion, cmd.rates());
        ProvisionPolicy saved = repository.save(policy);
        log.info("ProvisionPolicy created productType={} version={} policyId={}",
                cmd.productType(), nextVersion, saved.getPolicyId());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProvisionPolicy> listActive() {
        return repository.findAllActive();
    }

    @Override
    @Transactional(readOnly = true)
    public ProvisionPolicy getActiveByProductType(String productType) {
        return repository.findActiveByProductType(productType)
                .orElseThrow(() -> new ProvisionPolicyNotFoundException("ACTIVE policy for " + productType));
    }
}
