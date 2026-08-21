package com.fintech.commission.application.port.out;

import com.fintech.commission.domain.CommissionPolicy;
import com.fintech.commission.domain.CommissionType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommissionPolicyRepository {
    Optional<CommissionPolicy> findById(UUID policyId);
    /** Override por distribuidor, si existe. */
    Optional<CommissionPolicy> findActiveByProductAndTypeAndDistributor(
            String productType, CommissionType commissionType, UUID distributorPartyId);
    /** Tasa por defecto del producto (sin distribuidor). */
    Optional<CommissionPolicy> findActiveDefaultByProductAndType(String productType, CommissionType commissionType);
    List<CommissionPolicy> findAllActive();
    int maxVersionFor(String productType, CommissionType commissionType, UUID distributorPartyId);
    CommissionPolicy save(CommissionPolicy policy);
}
