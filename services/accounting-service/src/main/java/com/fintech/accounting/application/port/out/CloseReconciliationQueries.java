package com.fintech.accounting.application.port.out;

import com.fintech.accounting.application.CloseReport.CloseCheck;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The checks of a month-end close, run against accounting's own records: the ledger, the subledger balances it keeps
 * from the portfolio's facts (the shadows), the reserves risk measured, the deferred fees and the days the portfolio
 * closed. Read-only.
 */
public interface CloseReconciliationQueries {

    /**
     * @param period       the month, {@code YYYYMM}
     * @param lastDay      its last day
     * @param lastDayStart the first instant of its last day, in the accounting zone
     * @param amortized    whether the month's share of the deferred fees was already booked (a close books it before
     *                     reconciling; a preview does not, and reports it as pending)
     */
    List<CloseCheck> reconcile(String period, LocalDate lastDay, Instant lastDayStart, boolean amortized);

    /**
     * The year's revenue and expense balances by unit, product and account, over its twelve months
     * ({@code YYYY01..YYYY12}): what the year-end close takes to zero. {@code balance} is debits − credits (positive for
     * an expense).
     */
    List<YearBalance> yearResult(int year);

    record YearBalance(String orgUnitCode, String productType, String accountCode, BigDecimal balance) {}
}
