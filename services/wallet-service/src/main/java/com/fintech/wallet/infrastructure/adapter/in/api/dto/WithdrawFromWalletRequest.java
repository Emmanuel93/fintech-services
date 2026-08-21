package com.fintech.wallet.infrastructure.adapter.in.api.dto;

import com.fintech.wallet.domain.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record WithdrawFromWalletRequest(
        @NotNull PaymentMethod method,
        @NotNull @DecimalMin(value = "0.01", message = "Amount must be positive") BigDecimal amount,
        @NotBlank String payeeAccount
) {}
