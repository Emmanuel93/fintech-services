package com.fintech.charges.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.util.UUID;

public record ChargeReversedOutboundPayload(
        String eventId,
        UUID creditAccountId,
        String originalChargeType,
        BigDecimal amount,
        boolean waived
) {}
