package com.fintech.identity.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ClientTokenRequest(
        @NotBlank String clientId,
        @NotBlank String clientSecret
) {}
