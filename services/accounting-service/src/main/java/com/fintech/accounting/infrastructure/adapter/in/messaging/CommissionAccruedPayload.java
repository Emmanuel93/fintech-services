package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CommissionAccruedPayload(
        UUID commissionId,
        UUID creditAccountId,
        BigDecimal amount,
        java.time.Instant occurredOn
) {}
