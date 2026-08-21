package com.fintech.identity.application.port.out;

import com.fintech.identity.domain.CredentialType;
import com.fintech.identity.domain.IdentityCredential;

import java.util.Optional;
import java.util.UUID;

public interface CredentialRepository {

    Optional<IdentityCredential> findByUsername(String username);

    Optional<IdentityCredential> findByPartyIdAndCredentialType(UUID partyId, CredentialType type);

    /** Retorna cualquier credencial activa del party; útil para obtener username en flujos MFA. */
    Optional<IdentityCredential> findFirstByPartyId(UUID partyId);

    IdentityCredential save(IdentityCredential credential);
}
