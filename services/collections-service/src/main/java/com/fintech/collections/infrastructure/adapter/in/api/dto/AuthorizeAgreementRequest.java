package com.fintech.collections.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AuthorizeAgreementRequest(
        @NotBlank String authorizedBy,
        @NotBlank String authorizationRef
) {}
