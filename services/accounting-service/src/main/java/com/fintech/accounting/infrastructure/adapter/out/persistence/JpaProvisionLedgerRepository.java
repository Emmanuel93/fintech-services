package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.ProvisionLedgerRepository;
import com.fintech.accounting.domain.ProvisionLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JpaProvisionLedgerRepository
        extends JpaRepository<ProvisionLedgerEntry, UUID>, ProvisionLedgerRepository {
}
