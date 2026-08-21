package com.fintech.identity.application.port.in;

import com.fintech.identity.domain.CredentialType;

import java.util.UUID;

public interface CreateCredentialUseCase {

    void createCredential(UUID partyId, String username, CredentialType type, String password);
}
