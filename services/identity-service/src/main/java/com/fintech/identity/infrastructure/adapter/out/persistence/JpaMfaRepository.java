package com.fintech.identity.infrastructure.adapter.out.persistence;

import com.fintech.identity.application.port.out.MfaRepository;
import com.fintech.identity.domain.MfaConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
interface JpaMfaConfigJpaRepository extends JpaRepository<MfaConfig, UUID> {
    Optional<MfaConfig> findByPartyId(UUID partyId);
}

@Repository
class JpaMfaRepository implements MfaRepository {

    private final JpaMfaConfigJpaRepository jpa;

    JpaMfaRepository(JpaMfaConfigJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<MfaConfig> findByPartyId(UUID partyId) {
        return jpa.findByPartyId(partyId);
    }

    @Override
    public MfaConfig save(MfaConfig mfaConfig) {
        return jpa.save(mfaConfig);
    }
}
