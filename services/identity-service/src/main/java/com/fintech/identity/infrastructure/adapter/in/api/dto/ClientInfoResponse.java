package com.fintech.identity.infrastructure.adapter.in.api.dto;

import com.fintech.identity.domain.ClientStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ClientInfoResponse(
        UUID id,
        String clientId,
        String clientName,
        ClientStatus status,
        List<String> roles,
        Instant expiresAt,
        Instant createdAt
) {}
