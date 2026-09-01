package com.fintech.banking.infrastructure.adapter.out.persistence;

import com.fintech.banking.domain.BankCloseSeal;
import com.fintech.banking.domain.BankMatch;
import com.fintech.banking.domain.BankStatementLine;
import com.fintech.banking.domain.SuspenseEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataBankStatementLineRepository extends JpaRepository<BankStatementLine, UUID> {
    Optional<BankStatementLine> findByBankAccountIdAndExternalId(UUID bankAccountId, String externalId);
    List<BankStatementLine> findByBankAccountIdAndBusinessDate(UUID bankAccountId, LocalDate businessDate);
}

interface SpringDataBankMatchRepository extends JpaRepository<BankMatch, UUID> {
}

interface SpringDataSuspenseEntryRepository extends JpaRepository<SuspenseEntry, UUID> {
    List<SuspenseEntry> findByBankAccountIdAndStatusAndBusinessDateLessThanEqual(
            UUID bankAccountId, String status, LocalDate businessDate);
}

interface SpringDataBankCloseSealRepository extends JpaRepository<BankCloseSeal, UUID> {
    Optional<BankCloseSeal> findByBankAccountIdAndBusinessDateAndPeriodType(
            UUID bankAccountId, LocalDate businessDate, String periodType);
}
