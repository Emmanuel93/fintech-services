package com.fintech.commission.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.util.UUID;

/** Outbound {@code commission.commission-liquidated} — T4 ejecuta el SPEI y registra el gasto. */
public record CommissionLiquidatedPayload(
        UUID batchId,
        UUID beneficiaryPartyId,
        String period,
        BigDecimal totalAmount
) {}
