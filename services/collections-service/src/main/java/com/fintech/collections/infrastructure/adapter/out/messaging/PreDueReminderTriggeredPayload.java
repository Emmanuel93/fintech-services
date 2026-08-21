package com.fintech.collections.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

record PreDueReminderTriggeredPayload(
        UUID creditAccountId,
        UUID obligorPartyId,
        LocalDate dueDate,
        BigDecimal installmentAmount,
        Instant occurredOn
) {}
