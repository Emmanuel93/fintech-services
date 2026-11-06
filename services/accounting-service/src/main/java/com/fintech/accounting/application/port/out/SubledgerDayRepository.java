package com.fintech.accounting.application.port.out;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The days the portfolio closed. A day closed with accounts held out of its accrual (quarantined or held by charges)
 * is complete only once each of them is released.
 */
public interface SubledgerDayRepository {

    /** The portfolio closed {@code day}; {@code held} are the accounts still to accrue it. Recording twice is a no-op. */
    void dayClosed(LocalDate day, int accounts, List<UUID> held);

    /** A held account accrued {@code day}. */
    void accountReleased(LocalDate day, UUID creditAccountId);
}
