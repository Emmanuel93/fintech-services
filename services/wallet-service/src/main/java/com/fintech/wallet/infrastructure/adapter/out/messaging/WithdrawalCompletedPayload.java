package com.fintech.wallet.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record WithdrawalCompletedPayload(
        UUID withdrawalId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String method,
        BigDecimal amount,
        String payeeAccount,
        String externalRef,
        Instant occurredOn
) {}
