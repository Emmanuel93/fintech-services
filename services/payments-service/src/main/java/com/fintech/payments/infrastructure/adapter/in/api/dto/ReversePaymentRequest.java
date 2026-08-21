package com.fintech.payments.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ReversePaymentRequest(
        @NotBlank String reason
) {}
