package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CommissionLiquidatedPayload(
        UUID batchId,
        BigDecimal totalAmount,
        java.time.Instant occurredOn
) {}
