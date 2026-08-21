package com.fintech.disbursement.infrastructure.adapter.out.persistence;

import com.fintech.disbursement.application.port.out.CompanyMappingRepository;
import com.fintech.disbursement.domain.CompanyMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JpaCompanyMappingRepository
        extends JpaRepository<CompanyMapping, UUID>, CompanyMappingRepository {

    @Override
    default Optional<CompanyMapping> find(String sourceSystem, String sourceKey) {
        return findBySourceSystemAndSourceKey(sourceSystem, sourceKey);
    }

    Optional<CompanyMapping> findBySourceSystemAndSourceKey(String sourceSystem, String sourceKey);
}
