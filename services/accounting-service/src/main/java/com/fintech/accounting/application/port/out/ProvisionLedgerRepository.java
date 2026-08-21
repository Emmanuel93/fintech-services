package com.fintech.accounting.application.port.out;

import com.fintech.accounting.domain.ProvisionLedgerEntry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProvisionLedgerRepository {
    Optional<ProvisionLedgerEntry> findById(UUID creditAccountId);
    List<ProvisionLedgerEntry> findAll();
    ProvisionLedgerEntry save(ProvisionLedgerEntry entry);
}
