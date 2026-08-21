package com.fintech.wallet.infrastructure.adapter.in.api.dto;

import com.fintech.wallet.domain.PaymentInstruction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentInstructionResponse(
        UUID instructionId,
        UUID creditAccountId,
        String paymentMethod,
        BigDecimal amount,
        String paymentType,
        String status,
        Instant expiresAt,
        String paymentRef,
        Instant createdAt
) {
    public static PaymentInstructionResponse from(PaymentInstruction pi) {
        return new PaymentInstructionResponse(
                pi.getInstructionId(), pi.getCreditAccountId(),
                pi.getPaymentMethod(), pi.getAmount(), pi.getPaymentType(),
                pi.getStatus(), pi.getExpiresAt(), pi.getPaymentRef(), pi.getCreatedAt());
    }
}
