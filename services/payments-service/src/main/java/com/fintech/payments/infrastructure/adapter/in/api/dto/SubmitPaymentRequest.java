package com.fintech.payments.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record SubmitPaymentRequest(
        @NotNull UUID creditAccountId,
        @NotNull UUID obligorPartyId,
        @NotNull @DecimalMin(value = "0.01", message = "amount must be positive") BigDecimal amount,
        @NotBlank String paymentMethod,
        @NotBlank String externalRef
) {}
