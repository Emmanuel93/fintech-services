package com.fintech.payments.infrastructure.adapter.out.persistence;

import com.fintech.payments.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.payments.domain.AccountBalanceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JpaAccountBalanceSnapshotRepository
        extends JpaRepository<AccountBalanceSnapshot, UUID>, AccountBalanceSnapshotRepository {

    @Override
    Optional<AccountBalanceSnapshot> findByCreditAccountId(UUID creditAccountId);

    @Override
    boolean existsByCreditAccountId(UUID creditAccountId);
}
