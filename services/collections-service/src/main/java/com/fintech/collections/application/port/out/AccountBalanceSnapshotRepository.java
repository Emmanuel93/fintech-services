package com.fintech.collections.application.port.out;

import com.fintech.collections.domain.AccountBalanceSnapshot;
import java.util.Optional;
import java.util.UUID;

public interface AccountBalanceSnapshotRepository {
    Optional<AccountBalanceSnapshot> findById(UUID creditAccountId);
    AccountBalanceSnapshot save(AccountBalanceSnapshot snapshot);
}
