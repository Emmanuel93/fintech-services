package com.fintech.banking.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What treasury's self-checks read from its own data, and nothing else. */
public interface TreasurySelfCheckQueries {

    /** A funding source's balances next to what its movements add up to. */
    record FundingFigures(UUID sourceId, String kind, String counterparty, BigDecimal contributed, BigDecimal principal,
                          BigDecimal interestPayable, BigDecimal contributions, BigDecimal drawnLessRepaid,
                          BigDecimal accruedLessPaid) {}

    /** An interest accrual of a funding line: what it booked, what its window and rate give, the window before it. */
    record Accrual(UUID sourceId, LocalDate from, LocalDate through, BigDecimal amount, BigDecimal expected,
                   LocalDate previousThrough) {}

    /** A settled order the cash book and the payout trace do not agree on. */
    record PayoutMismatch(String trackingKey, BigDecimal cash, BigDecimal trace) {}

    /** A day the cash book closed below zero. */
    record CashDay(LocalDate businessDate, BigDecimal balance) {}

    /** A payout trace that stopped before any outcome. */
    record StaleTrace(UUID payoutId, String status, LocalDate businessDate, BigDecimal amount) {}

    List<FundingFigures> funding();

    List<Accrual> accruals();

    List<PayoutMismatch> payoutMismatches();

    /** How many settled orders the cash book holds: the scope of the comparison with the trace. */
    int settledOrders();

    List<CashDay> negativeDays();

    int cashDays();

    /** Traces still routed or dispatched whose operation day (or creation) is before {@code day}. */
    List<StaleTrace> tracesStoppedBefore(LocalDate day);
}
