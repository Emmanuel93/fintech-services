package com.fintech.collections.infrastructure.adapter.out.persistence;

import com.fintech.collections.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.collections.domain.AccountBalanceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JpaAccountBalanceSnapshotRepository
        extends JpaRepository<AccountBalanceSnapshot, UUID>, AccountBalanceSnapshotRepository {
}
