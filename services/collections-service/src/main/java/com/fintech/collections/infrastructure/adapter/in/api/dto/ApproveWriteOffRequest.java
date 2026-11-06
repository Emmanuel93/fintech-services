package com.fintech.collections.infrastructure.adapter.in.api.dto;

import com.fintech.collections.domain.WriteOffReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ApproveWriteOffRequest(
        @NotNull WriteOffReason reason,
        String authorizedBy,           // ignored: the authorizer is the session
        @NotBlank String authorizationRef
) {}
