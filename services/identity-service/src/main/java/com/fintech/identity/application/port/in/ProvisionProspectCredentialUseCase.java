package com.fintech.identity.application.port.in;

import java.util.UUID;

public interface ProvisionProspectCredentialUseCase {

    /**
     * Creates a PASSWORD credential for a newly registered prospect.
     * Receives the raw password — identity-service is responsible for BCrypt hashing.
     * Idempotent: silently returns if the credential already exists.
     */
    void provisionFromProspect(UUID prospectId, String username, String rawPassword);
}
