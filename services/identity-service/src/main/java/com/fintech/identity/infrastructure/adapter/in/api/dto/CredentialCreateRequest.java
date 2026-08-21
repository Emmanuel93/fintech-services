package com.fintech.identity.infrastructure.adapter.in.api.dto;

import com.fintech.identity.domain.CredentialType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CredentialCreateRequest(
        @NotNull UUID partyId,
        @NotBlank String username,
        @NotBlank String password,
        @NotNull CredentialType credentialType
) {}
