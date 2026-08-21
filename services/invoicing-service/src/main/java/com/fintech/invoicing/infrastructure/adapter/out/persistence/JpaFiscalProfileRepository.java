package com.fintech.invoicing.infrastructure.adapter.out.persistence;

import com.fintech.invoicing.application.port.out.FiscalProfileRepository;
import com.fintech.invoicing.domain.FiscalProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JpaFiscalProfileRepository
        extends JpaRepository<FiscalProfile, UUID>, FiscalProfileRepository {

    @Override
    Optional<FiscalProfile> findByProspectId(UUID prospectId);
}
