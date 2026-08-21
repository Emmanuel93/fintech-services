package com.fintech.payments.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentReversedOutboundPayload(
        String eventId,
        UUID creditAccountId,
        BigDecimal amount,
        String reason
) {}
