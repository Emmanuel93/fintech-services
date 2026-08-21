package com.fintech.origination.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SignContractRequest(
        @NotBlank @Pattern(regexp = "\\d{18}", message = "CLABE must be 18 digits") String clabeAccount,
        @NotBlank String signatureProof,
        String documentRef
) {}
