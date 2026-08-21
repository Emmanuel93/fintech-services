package com.fintech.risk.application.port.out;

import com.fintech.risk.domain.ProvisionPolicy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProvisionPolicyRepository {
    Optional<ProvisionPolicy> findById(UUID policyId);
    /** PP-01: the single ACTIVE policy for a productType (drives the provision rate). */
    Optional<ProvisionPolicy> findActiveByProductType(String productType);
    List<ProvisionPolicy> findAllActive();
    /** Highest business version seen for a productType — for assigning the next version on create. */
    int maxVersionForProductType(String productType);
    ProvisionPolicy save(ProvisionPolicy policy);
}
