package com.fintech.charges.application.port.out;

import com.fintech.charges.domain.AccountBalanceSnapshot;

import java.util.Optional;
import java.util.UUID;

public interface AccountBalanceSnapshotRepository {
    Optional<AccountBalanceSnapshot> findByCreditAccountId(UUID creditAccountId);
    boolean existsByCreditAccountId(UUID creditAccountId);
    AccountBalanceSnapshot save(AccountBalanceSnapshot snapshot);
}
