package com.fintech.stp.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterOrderingAccountRequest(
        @NotBlank @Size(min = 18, max = 18) String clabe,
        @NotBlank String holderName,
        String taxId,
        String accountType,
        String stpClientNumber,
        boolean defaultAccount
) {}
