package com.fintech.risk.infrastructure.adapter.out.persistence;

import com.fintech.risk.application.port.out.ProvisionPolicyRepository;
import com.fintech.risk.domain.ProvisionPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaProvisionPolicyRepository
        extends JpaRepository<ProvisionPolicy, UUID>, ProvisionPolicyRepository {

    @Override
    @Query("SELECT p FROM ProvisionPolicy p WHERE p.productType = :productType AND p.status = 'ACTIVE'")
    Optional<ProvisionPolicy> findActiveByProductType(@Param("productType") String productType);

    @Override
    @Query("SELECT p FROM ProvisionPolicy p WHERE p.status = 'ACTIVE'")
    List<ProvisionPolicy> findAllActive();

    @Override
    @Query("SELECT COALESCE(MAX(p.version), 0) FROM ProvisionPolicy p WHERE p.productType = :productType")
    int maxVersionForProductType(@Param("productType") String productType);
}
