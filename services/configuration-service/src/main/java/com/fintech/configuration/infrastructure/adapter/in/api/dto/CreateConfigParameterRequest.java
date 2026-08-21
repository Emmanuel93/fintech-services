package com.fintech.configuration.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateConfigParameterRequest(
        @NotBlank @Size(max = 100) String paramKey,
        @NotBlank String value,
        String productType,
        String channelType,
        LocalDate effectiveDate
) {}
