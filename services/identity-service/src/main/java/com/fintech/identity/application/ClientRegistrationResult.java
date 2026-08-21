package com.fintech.identity.application;

import com.fintech.identity.domain.ClientStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ClientRegistrationResult(
        UUID id,
        String clientId,
        String clientSecret,
        String clientName,
        ClientStatus status,
        List<String> roles,
        Instant expiresAt,
        Instant createdAt
) {}
