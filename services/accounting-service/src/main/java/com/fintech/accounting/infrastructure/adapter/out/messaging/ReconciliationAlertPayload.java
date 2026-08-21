package com.fintech.accounting.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReconciliationAlertPayload(
        String checkType,
        UUID creditAccountId,
        BigDecimal operationalBalance,
        BigDecimal accountingBalance,
        BigDecimal delta,
        String period,
        Instant detectedAt
) {}
