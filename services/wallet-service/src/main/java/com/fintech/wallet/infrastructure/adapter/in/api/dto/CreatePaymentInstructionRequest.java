package com.fintech.wallet.infrastructure.adapter.in.api.dto;

import com.fintech.wallet.domain.PaymentMethod;
import com.fintech.wallet.domain.PaymentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public record CreatePaymentInstructionRequest(
        @NotNull PaymentMethod paymentMethod,
        // amount is optional for MINIMUM and SETTLEMENT — resolved from WalletView
        @DecimalMin(value = "0.01", message = "Amount must be positive") BigDecimal amount,
        @NotNull PaymentType paymentType,
        Instant scheduledAt   // only for DOMICILIACION
) {}
