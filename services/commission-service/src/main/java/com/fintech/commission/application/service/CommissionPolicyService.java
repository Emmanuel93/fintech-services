package com.fintech.commission.application.service;

import com.fintech.commission.application.CreateCommissionPolicyCommand;
import com.fintech.commission.application.port.in.CommissionPolicyUseCase;
import com.fintech.commission.application.port.out.CommissionPolicyRepository;
import com.fintech.commission.domain.CommissionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Versioned rates — one ACTIVE per (productType, commissionType, distributor), same maker pattern as ProvisionPolicy (D9). */
@Service
@Transactional
public class CommissionPolicyService implements CommissionPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(CommissionPolicyService.class);

    private final CommissionPolicyRepository repository;

    public CommissionPolicyService(CommissionPolicyRepository repository) {
        this.repository = repository;
    }

    @Override
    public CommissionPolicy create(CreateCommissionPolicyCommand cmd) {
        Optional<CommissionPolicy> prior = cmd.distributorPartyId() != null
                ? repository.findActiveByProductAndTypeAndDistributor(cmd.productType(), cmd.commissionType(), cmd.distributorPartyId())
                : repository.findActiveDefaultByProductAndType(cmd.productType(), cmd.commissionType());
        prior.ifPresent(p -> { p.deprecate(); repository.save(p); });

        int nextVersion = repository.maxVersionFor(cmd.productType(), cmd.commissionType(), cmd.distributorPartyId()) + 1;
        CommissionPolicy policy = CommissionPolicy.create(
                cmd.productType(), cmd.distributorPartyId(), cmd.commissionType(), cmd.rate(), nextVersion);
        CommissionPolicy saved = repository.save(policy);
        log.info("CommissionPolicy created productType={} distributor={} type={} rate={} version={}",
                cmd.productType(), cmd.distributorPartyId(), cmd.commissionType(), cmd.rate(), nextVersion);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommissionPolicy> listActive() {
        return repository.findAllActive();
    }
}
