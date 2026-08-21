package com.fintech.creditportfolio.application.port.out;

import com.fintech.creditportfolio.domain.BalanceEvent;

public interface BalanceEventRepository {

    BalanceEvent save(BalanceEvent event);

    /** Idempotency guard — true if this source event was already applied. */
    boolean existsBySourceEventId(String sourceEventId);

    /** Returns how many balance events have been recorded for a given account (for test assertions). */
    long countByAccountId(java.util.UUID accountId);
}
