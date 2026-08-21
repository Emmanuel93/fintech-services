package com.fintech.identity.infrastructure.adapter.out.persistence;

import com.fintech.identity.application.port.out.CredentialRepository;
import com.fintech.identity.domain.CredentialType;
import com.fintech.identity.domain.IdentityCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JpaCredentialRepository
        extends JpaRepository<IdentityCredential, UUID>, CredentialRepository {

    @Override
    Optional<IdentityCredential> findByUsername(String username);

    @Override
    Optional<IdentityCredential> findByPartyIdAndCredentialType(UUID partyId, CredentialType type);

    @Override
    Optional<IdentityCredential> findFirstByPartyId(UUID partyId);
}
