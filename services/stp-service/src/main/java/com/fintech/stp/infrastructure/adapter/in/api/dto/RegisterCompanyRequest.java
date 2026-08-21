package com.fintech.stp.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterCompanyRequest(
        @NotBlank String code,
        @NotBlank String stpEmpresa,
        @NotNull Integer institucionOperante,
        @NotBlank @Size(max = 4) String trackingPrefix,
        String clabeBankCode,
        String clabePlazaCode,
        String clabeClientPrefix
) {}
