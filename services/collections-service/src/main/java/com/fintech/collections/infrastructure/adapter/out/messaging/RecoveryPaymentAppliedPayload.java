package com.fintech.collections.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record RecoveryPaymentAppliedPayload(
        UUID writeOffId,
        UUID creditAccountId,
        BigDecimal recoveredAmount,
        String paymentMethod,
        Instant occurredOn,
        /** The payment order recovered: the idempotency key of the recovery in accounting. */
        UUID paymentId,
        /** The SPEI credit that paid it, when it was one: the item of collections in transit it moves. */
        UUID inboundCreditId
) {}
