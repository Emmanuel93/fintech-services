package com.fintech.payments.application.port.out;

import com.fintech.payments.domain.AccountBalanceSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountBalanceSnapshotRepository {
    Optional<AccountBalanceSnapshot> findByCreditAccountId(UUID creditAccountId);
    boolean existsByCreditAccountId(UUID creditAccountId);
    AccountBalanceSnapshot save(AccountBalanceSnapshot snapshot);

    /** Every loan of a payer: the candidates of a received credit's prelation. */
    List<AccountBalanceSnapshot> findByObligorPartyId(UUID obligorPartyId);
}
