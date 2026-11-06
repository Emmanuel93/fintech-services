package com.fintech.accounting.application.port.out;

import java.time.Instant;
import java.util.Optional;

/** The record of each month's close: the reconciliation it ran and the trial balance it published. */
public interface PeriodCloseRepository {

    record StoredClose(String period, String outcome, Instant runAt, int attempts, String checksJson,
                       String trialBalanceJson) {}

    /** Saves a close attempt; a later one replaces it and counts as one more attempt. */
    default void save(String period, String outcome, Instant runAt, String checksJson, String trialBalanceJson) {
        save(period, outcome, runAt, checksJson, trialBalanceJson, null);
    }

    /** Saves a close attempt with the trial balance by product and branch it published next to the whole one. */
    void save(String period, String outcome, Instant runAt, String checksJson, String trialBalanceJson,
              String trialBalanceByProductJson);

    Optional<StoredClose> find(String period);
}
