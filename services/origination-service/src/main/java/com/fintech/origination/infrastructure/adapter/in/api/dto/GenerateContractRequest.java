package com.fintech.origination.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;

public record GenerateContractRequest(
        @NotBlank String signatureMethod
) {}
