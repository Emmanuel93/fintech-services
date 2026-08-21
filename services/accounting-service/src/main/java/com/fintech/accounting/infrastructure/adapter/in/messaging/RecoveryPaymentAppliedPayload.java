package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/** Inbound {@code collections.recovery-payment-applied} — recuperación post-quebranto (ingreso extraordinario). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecoveryPaymentAppliedPayload(
        UUID writeOffId,
        UUID creditAccountId,
        BigDecimal recoveredAmount,
        String paymentMethod,
        java.time.Instant occurredOn
) {}
