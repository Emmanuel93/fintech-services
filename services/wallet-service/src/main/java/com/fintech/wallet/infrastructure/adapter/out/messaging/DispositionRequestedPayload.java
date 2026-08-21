package com.fintech.wallet.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record DispositionRequestedPayload(
        UUID dispositionRequestId,
        UUID creditAccountId,
        UUID obligorPartyId,
        BigDecimal amount,
        String dispositionType,
        UUID beneficiaryPartyId,
        String payeeAccount,
        Integer termPeriods,
        Instant occurredOn
) {}
