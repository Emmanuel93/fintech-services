package com.fintech.disbursement.application.port.out;

import com.fintech.disbursement.domain.CompanyMapping;

import java.util.List;
import java.util.Optional;

public interface CompanyMappingRepository {

    CompanyMapping save(CompanyMapping mapping);

    Optional<CompanyMapping> find(String sourceSystem, String sourceKey);

    List<CompanyMapping> findAll();
}
