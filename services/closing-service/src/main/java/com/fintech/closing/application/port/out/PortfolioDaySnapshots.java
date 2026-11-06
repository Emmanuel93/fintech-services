package com.fintech.closing.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The portfolio's per-account day snapshot, as closing needs it: how an account stood at the end of D.
 * The cutoff of D seals its days past due from here, and checks its own principal at D against it.
 */
public interface PortfolioDaySnapshots {

    /** The snapshot rows of these accounts for {@code businessDate}; accounts the portfolio did not photograph are absent. */
    List<AccountSnapshot> lookup(LocalDate businessDate, Collection<UUID> creditAccountIds);

    record AccountSnapshot(UUID creditAccountId, BigDecimal principal, int daysDelinquent, Instant takenAt) {}
}
