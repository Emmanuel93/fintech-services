package com.fintech.commission.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** The account a beneficiary's commission lots are paid to. */
public record PayoutAccountRequest(
        @NotBlank @Pattern(regexp = "\\d{18}", message = "la CLABE tiene 18 dígitos") String clabe,
        String holderName
) {}
