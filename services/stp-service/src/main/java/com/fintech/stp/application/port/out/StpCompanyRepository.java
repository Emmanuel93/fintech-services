package com.fintech.stp.application.port.out;

import com.fintech.stp.domain.StpCompany;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StpCompanyRepository {

    Optional<StpCompany> findById(UUID companyId);

    Optional<StpCompany> findByCode(String code);

    List<StpCompany> findAll();

    /** Empresas con órdenes en vuelo: lo que el poller necesita recorrer. */
    List<StpCompany> findAllActive();

    StpCompany save(StpCompany company);
}
