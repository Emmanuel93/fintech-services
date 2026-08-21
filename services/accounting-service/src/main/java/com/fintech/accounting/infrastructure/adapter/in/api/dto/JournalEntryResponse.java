package com.fintech.accounting.infrastructure.adapter.in.api.dto;

import com.fintech.accounting.domain.JournalEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record JournalEntryResponse(
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
        String description,
        Instant postingDate
) {
    public static JournalEntryResponse from(JournalEntry e) {
        return new JournalEntryResponse(e.getEntryId(), e.getSourceEventId(), e.getTriggerEvent(),
                e.getCreditAccountId(), e.getObligorPartyId(), e.getDebitAccount(), e.getCreditAccount(),
                e.getAmount(), e.getCurrency(), e.getPeriod(), e.getDescription(), e.getPostingDate());
    }
}
