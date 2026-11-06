package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Inbound {@code collections.recovery-payment-applied} — recuperación post-quebranto (ingreso extraordinario). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecoveryPaymentAppliedPayload(
        UUID writeOffId,
        UUID creditAccountId,
        BigDecimal recoveredAmount,
        String paymentMethod,
        Instant occurredOn,
        /** The payment order recovered: each recovery is its own fact. Null in older producers. */
        UUID paymentId,
        /** The SPEI credit that paid it, when it was one: the item of collections in transit it moves. */
        UUID inboundCreditId
) {}
