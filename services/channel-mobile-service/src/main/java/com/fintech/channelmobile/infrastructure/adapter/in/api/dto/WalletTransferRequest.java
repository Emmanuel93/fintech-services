package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record WalletTransferRequest(
        @NotBlank String recipientPhone,
        @Positive double amount,
        @NotBlank String concept
) {}
