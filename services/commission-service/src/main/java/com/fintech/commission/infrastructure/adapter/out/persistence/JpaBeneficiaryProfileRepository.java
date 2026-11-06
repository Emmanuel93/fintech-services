package com.fintech.commission.infrastructure.adapter.out.persistence;

import com.fintech.commission.application.port.out.BeneficiaryProfileRepository;
import com.fintech.commission.domain.BeneficiaryProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JpaBeneficiaryProfileRepository
        extends JpaRepository<BeneficiaryProfile, UUID>, BeneficiaryProfileRepository {
}
