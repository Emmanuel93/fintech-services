package com.fintech.closing.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** El sello del día: las cifras contra las que concilian contabilidad y bancos. */
public record DaySealedPayload(
        String sealId,
        LocalDate businessDate,
        String phase,
        String scopeKey,
        int unitCount,
        BigDecimal totalPrincipal,
        BigDecimal totalInterest,
        BigDecimal totalDebt,
        String contentHash,
        Instant sealedAt
) {}
