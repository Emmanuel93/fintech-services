package com.fintech.collections.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record RecoveryPaymentAppliedPayload(
        UUID writeOffId,
        UUID creditAccountId,
        BigDecimal recoveredAmount,
        String paymentMethod,
        Instant occurredOn
) {}
