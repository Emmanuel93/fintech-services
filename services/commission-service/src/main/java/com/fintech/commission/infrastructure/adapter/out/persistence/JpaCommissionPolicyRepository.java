package com.fintech.commission.infrastructure.adapter.out.persistence;

import com.fintech.commission.application.port.out.CommissionPolicyRepository;
import com.fintech.commission.domain.CommissionPolicy;
import com.fintech.commission.domain.CommissionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaCommissionPolicyRepository
        extends JpaRepository<CommissionPolicy, UUID>, CommissionPolicyRepository {

    @Override
    @Query("SELECT p FROM CommissionPolicy p WHERE p.productType = :productType AND p.commissionType = :type " +
           "AND p.distributorPartyId = :distributorPartyId AND p.status = 'ACTIVE'")
    Optional<CommissionPolicy> findActiveByProductAndTypeAndDistributor(
            @Param("productType") String productType, @Param("type") CommissionType commissionType,
            @Param("distributorPartyId") UUID distributorPartyId);

    @Override
    @Query("SELECT p FROM CommissionPolicy p WHERE p.productType = :productType AND p.commissionType = :type " +
           "AND p.distributorPartyId IS NULL AND p.status = 'ACTIVE'")
    Optional<CommissionPolicy> findActiveDefaultByProductAndType(
            @Param("productType") String productType, @Param("type") CommissionType commissionType);

    @Override
    @Query("SELECT p FROM CommissionPolicy p WHERE p.status = 'ACTIVE'")
    List<CommissionPolicy> findAllActive();

    @Override
    @Query("SELECT COALESCE(MAX(p.version), 0) FROM CommissionPolicy p WHERE p.productType = :productType " +
           "AND p.commissionType = :type AND " +
           "((:distributorPartyId IS NULL AND p.distributorPartyId IS NULL) OR p.distributorPartyId = :distributorPartyId)")
    int maxVersionFor(@Param("productType") String productType, @Param("type") CommissionType commissionType,
                      @Param("distributorPartyId") UUID distributorPartyId);
}
