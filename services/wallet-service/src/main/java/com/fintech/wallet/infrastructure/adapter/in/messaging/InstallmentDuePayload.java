package com.fintech.wallet.infrastructure.adapter.in.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InstallmentDuePayload(
        UUID installmentId,
        UUID creditAccountId,
        int installmentNumber,
        LocalDate dueDate,
        BigDecimal totalAmount,
        Instant occurredAt
) {}
