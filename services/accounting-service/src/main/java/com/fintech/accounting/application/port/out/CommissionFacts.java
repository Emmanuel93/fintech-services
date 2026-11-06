package com.fintech.accounting.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Commission's facts, signed as they move what is owed to each beneficiary for each product: what 2120 is reconciled
 * against at the close. Recording the same fact twice keeps the first.
 */
public interface CommissionFacts {

    enum Kind { ACCRUED, REVERSED, LOT_SENT, LOT_FAILED }

    /**
     * @param gross signed: an accrual or a failed lot adds what is owed, a reversal or a lot's SPEI requested takes it
     * @param businessDate the business day of the fact, which decides its period
     */
    record Fact(String factId, Kind kind, UUID beneficiaryPartyId, String productType, UUID batchId, Integer attempt,
                BigDecimal gross, LocalDate businessDate) {}

    void record(Fact fact);
}
