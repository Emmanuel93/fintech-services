package com.fintech.wallet.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

record WalletSnapshotUpdatedPayload(
        UUID walletId,
        UUID creditAccountId,
        BigDecimal totalDebt,
        BigDecimal availableCredit,
        BigDecimal minimumPayment,
        LocalDate paymentDueDate,
        Instant lastUpdatedAt
) {}
