package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.JournalEntryRepository;
import com.fintech.accounting.domain.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JpaJournalEntryRepository extends JpaRepository<JournalEntry, UUID>, JournalEntryRepository {

    @Override
    boolean existsBySourceEventIdAndDebitAccountAndCreditAccount(String sourceEventId, String debitAccount, String creditAccount);

    @Override
    List<JournalEntry> findByCreditAccountId(UUID creditAccountId);

    @Override
    List<JournalEntry> findByObligorPartyId(UUID obligorPartyId);

    @Override
    List<JournalEntry> findByPeriod(String period);
}
