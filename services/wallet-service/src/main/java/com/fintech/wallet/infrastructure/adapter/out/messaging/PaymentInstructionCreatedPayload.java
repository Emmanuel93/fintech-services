package com.fintech.wallet.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record PaymentInstructionCreatedPayload(
        UUID instructionId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String paymentMethod,
        BigDecimal amount,
        String paymentType,
        Instant expiresAt,
        String paymentRef,
        Instant occurredOn
) {}
