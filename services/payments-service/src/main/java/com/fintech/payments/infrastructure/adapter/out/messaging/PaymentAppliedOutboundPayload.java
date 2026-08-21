package com.fintech.payments.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentAppliedOutboundPayload(
        String eventId,
        UUID creditAccountId,
        BigDecimal amount,
        long snapshotVersion
) {}
