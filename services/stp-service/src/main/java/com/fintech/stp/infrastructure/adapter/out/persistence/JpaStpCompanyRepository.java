package com.fintech.stp.infrastructure.adapter.out.persistence;

import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.domain.StpCompany;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaStpCompanyRepository
        extends JpaRepository<StpCompany, UUID>, StpCompanyRepository {

    @Override
    Optional<StpCompany> findByCode(String code);

    @Override
    @Query("SELECT c FROM StpCompany c WHERE c.status = 'ACTIVE'")
    List<StpCompany> findAllActive();
}
