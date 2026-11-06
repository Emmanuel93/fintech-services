package com.fintech.collections.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AuthorizeAgreementRequest(
        String authorizedBy,           // ignored: the authorizer is the session
        @NotBlank String authorizationRef
) {}
