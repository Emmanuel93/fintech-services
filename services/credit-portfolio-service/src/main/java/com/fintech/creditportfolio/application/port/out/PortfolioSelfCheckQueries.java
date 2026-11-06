package com.fintech.creditportfolio.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What the portfolio's self-checks read from its own data, and nothing else. */
public interface PortfolioSelfCheckQueries {

    /** An account whose debt is not what its ledger of balance events adds up to. */
    record LedgerGap(UUID creditAccountId, String contractNumber, BigDecimal debt, BigDecimal ledger) {}

    /** A day's photo of an account that did not match its ledger when it was taken. */
    record SnapshotGap(LocalDate businessDate, UUID creditAccountId, BigDecimal gap) {}

    /** A day close that did not finish clean. */
    record OpenClose(LocalDate businessDate, String status, int errors, String lastError) {}

    /** An account with a balance below zero. */
    record NegativeBalance(UUID creditAccountId, String contractNumber, BigDecimal principal, BigDecimal interest,
                           BigDecimal iva, BigDecimal penalty) {}

    /** A disposition still waiting for its payout's outcome. */
    record PendingDisposition(UUID dispositionId, UUID creditAccountId, BigDecimal amount, Instant createdAt) {}

    /**
     * A cancelled credit (its disbursement never happened, D63) that still owes something or keeps a live plan entry:
     * its compensation did not finish.
     */
    record UnfinishedCancellation(UUID creditAccountId, String contractNumber, BigDecimal debt, int liveInstallments) {}

    /** How many accounts there are and the debt they hold. */
    record Book(int accounts, BigDecimal debt) {}

    Book book();

    List<LedgerGap> ledgerGaps();

    /** The day photos of a month (YYYYMM), or of every day with none: how many, and those with a gap. */
    int snapshots(String period);

    List<SnapshotGap> snapshotGaps(String period);

    int dayCloses(LocalDate before);

    List<OpenClose> openClosesBefore(LocalDate day);

    List<NegativeBalance> negativeBalances();

    List<PendingDisposition> dispositionsInFlightBefore(Instant moment);

    /** How many credits are cancelled, and those whose compensation did not finish. */
    int cancelledAccounts();

    List<UnfinishedCancellation> unfinishedCancellations();
}
