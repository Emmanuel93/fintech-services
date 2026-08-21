package com.fintech.accounting.application.port.out;

import com.fintech.accounting.domain.JournalEntry;

import java.util.List;
import java.util.UUID;

public interface JournalEntryRepository {
    boolean existsBySourceEventIdAndDebitAccountAndCreditAccount(String sourceEventId, String debit, String credit);
    List<JournalEntry> findByCreditAccountId(UUID creditAccountId);
    List<JournalEntry> findByObligorPartyId(UUID obligorPartyId);
    List<JournalEntry> findByPeriod(String period);
    JournalEntry save(JournalEntry entry);
}
