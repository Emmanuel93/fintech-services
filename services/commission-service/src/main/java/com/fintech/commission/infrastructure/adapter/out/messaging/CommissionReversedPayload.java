package com.fintech.commission.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.util.UUID;

public record CommissionReversedPayload(
        UUID commissionId,
        UUID creditAccountId,
        UUID beneficiaryPartyId,
        BigDecimal amount
) {}
