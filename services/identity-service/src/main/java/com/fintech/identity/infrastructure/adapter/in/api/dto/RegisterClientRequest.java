package com.fintech.identity.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;

public record RegisterClientRequest(
        @NotBlank String clientId,
        @NotBlank String clientName,
        @NotEmpty List<String> roles,
        Instant expiresAt
) {}
