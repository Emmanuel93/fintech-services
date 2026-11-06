package com.fintech.accounting.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Each funding source as treasury last reported it: what the month-end close reconciles the ledger against. */
public interface FundingBalanceRepository {

    /** Keeps the balances a movement left; the accrual date moves only forward. */
    void record(UUID fundingSourceId, String kind, String counterpartyName, String contractReference,
                BigDecimal contributed, BigDecimal principal, BigDecimal interestPayable, LocalDate accruedThrough,
                String status, UUID movementId);
}
