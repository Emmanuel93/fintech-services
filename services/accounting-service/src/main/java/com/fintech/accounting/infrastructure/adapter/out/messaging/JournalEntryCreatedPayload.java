package com.fintech.accounting.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record JournalEntryCreatedPayload(
        UUID entryId,
        String sourceEventId,
        String triggerEvent,
        UUID creditAccountId,
        UUID obligorPartyId,
        String debitAccount,
        String creditAccount,
        BigDecimal amount,
        String currency,
        String period,
        Instant postingDate
) {}
