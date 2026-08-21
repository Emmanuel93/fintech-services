package com.fintech.identity.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record MfaEnrollConfirmRequest(
        @NotBlank @Pattern(regexp = "\\d{6}") String code
) {}
