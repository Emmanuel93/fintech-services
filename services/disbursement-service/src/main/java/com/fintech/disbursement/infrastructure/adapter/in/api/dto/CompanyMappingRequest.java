package com.fintech.disbursement.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CompanyMappingRequest(
        @NotBlank String sourceSystem,
        @NotBlank String sourceKey,
        @NotNull UUID companyId
) {}
