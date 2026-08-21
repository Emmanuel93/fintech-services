package com.fintech.channels.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record CaptureIntentRequest(
        @NotBlank String intentType,
        String productTypeHint,
        BigDecimal requestedAmount,
        String promoterCode
) {}
