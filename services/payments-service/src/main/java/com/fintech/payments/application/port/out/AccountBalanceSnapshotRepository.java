package com.fintech.payments.application.port.out;

import com.fintech.payments.domain.AccountBalanceSnapshot;

import java.util.Optional;
import java.util.UUID;

public interface AccountBalanceSnapshotRepository {
    Optional<AccountBalanceSnapshot> findByCreditAccountId(UUID creditAccountId);
    boolean existsByCreditAccountId(UUID creditAccountId);
    AccountBalanceSnapshot save(AccountBalanceSnapshot snapshot);
}
