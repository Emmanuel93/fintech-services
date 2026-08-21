package com.fintech.commission.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CommissionAccruedPayload(
        UUID commissionId,
        String commissionType,
        UUID creditAccountId,
        UUID beneficiaryPartyId,
        BigDecimal basis,
        BigDecimal rate,
        BigDecimal amount,
        String period,
        Instant accrualDate
) {}
