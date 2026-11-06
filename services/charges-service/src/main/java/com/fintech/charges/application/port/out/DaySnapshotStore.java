package com.fintech.charges.application.port.out;

import com.fintech.charges.application.port.out.PortfolioDaySnapshots.AccountSnapshot;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Charges' local copy of the portfolio's day snapshot ({@code charges.account_day_snapshots}). */
public interface DaySnapshotStore {

    /** Stores a page; a row already stored for the same day and account is left as it was. */
    int store(LocalDate businessDate, Collection<AccountSnapshot> accounts);

    int count(LocalDate businessDate);

    Optional<AccountSnapshot> find(LocalDate businessDate, UUID creditAccountId);
}
