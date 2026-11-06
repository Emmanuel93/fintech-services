package com.fintech.creditportfolio.application.port.out;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The per-account day snapshot: how every account stood when day D ended. It is the preliminary close
 * of the day, taken before charges accrues D, and what charges and closing read instead of the live
 * balance. One per day; taking it twice changes nothing.
 */
public interface DaySnapshotRepository {

    enum Claim { CLAIMED, ALREADY_DONE, IN_PROGRESS }

    /** Claims the day: when nobody has it, when it was waiting or failed, or when its owner went stale. */
    Claim claim(LocalDate businessDate, String triggeredBy, Duration staleAfter);

    /** The snapshot cannot be taken yet; the reason is written down. */
    void waiting(LocalDate businessDate, String triggeredBy, String reason);

    /**
     * Writes the snapshot of every live account at the end of {@code businessDate}: balances as of the
     * end of D (minus what was applied with a later value date) and delinquency as of D. Returns how many
     * accounts went in.
     *
     * @param endOfDay the instant D ended in the business zone; only accounts activated before it go in.
     */
    int take(LocalDate businessDate, Instant endOfDay);

    Summary summarize(LocalDate businessDate);

    void complete(LocalDate businessDate, Summary summary);

    void fail(LocalDate businessDate, String error);

    Optional<DaySnapshot> find(LocalDate businessDate);

    /** The accounts of a day's snapshot, paged and ordered by id: how charges and closing read it. */
    List<AccountSnapshot> page(LocalDate businessDate, UUID after, int size);

    List<AccountSnapshot> lookup(LocalDate businessDate, Collection<UUID> creditAccountIds);

    Optional<AccountSnapshot> find(LocalDate businessDate, UUID creditAccountId);

    /** One account's history, day by day: the step-by-step view behind a statement. */
    List<AccountSnapshot> history(UUID creditAccountId, LocalDate from, LocalDate to);

    /**
     * Control totals of a day's snapshot.
     *
     * @param ledgerGaps accounts whose ledger (sum of balance_events) does not equal their balances; must be zero
     */
    record Summary(int accounts, int delinquentAccounts, BigDecimal principalTotal, BigDecimal overdueTotal, int ledgerGaps) {

        public Summary(int accounts, int delinquentAccounts, BigDecimal principalTotal, BigDecimal overdueTotal) {
            this(accounts, delinquentAccounts, principalTotal, overdueTotal, 0);
        }
    }

    record DaySnapshot(LocalDate businessDate, String status, String triggeredBy, Integer accounts,
                       Integer delinquentAccounts, BigDecimal principalTotal, BigDecimal overdueTotal,
                       String waitingFor, String lastError, int attempts, Instant startedAt, Instant completedAt,
                       Integer ledgerGaps) {

        public DaySnapshot(LocalDate businessDate, String status, String triggeredBy, Integer accounts,
                           Integer delinquentAccounts, BigDecimal principalTotal, BigDecimal overdueTotal,
                           String waitingFor, String lastError, int attempts, Instant startedAt, Instant completedAt) {
            this(businessDate, status, triggeredBy, accounts, delinquentAccounts, principalTotal, overdueTotal,
                    waitingFor, lastError, attempts, startedAt, completedAt, 0);
        }

        public boolean done() { return "DONE".equals(status); }
    }

    /**
     * One account at the end of D.
     *
     * @param principal        outstanding principal at the end of D: the basis of D's ordinary interest
     * @param overduePrincipal principal of the installments due before D and unpaid: the moratorium basis
     * @param oldestDueDate    the oldest overdue installment; with the product's grace it decides when the moratorium starts
     * @param daysDelinquent   days past due as of D (D minus the oldest due date), zero when current
     * @param laterMovements   how many movements with a value date after D were subtracted
     * @param ledgerTotal      the sum of the account's ledger entries (balance_events) up to D
     * @param ledgerGap        ledger total minus total debt at D; zero when the subledger is intact
     * @param interestBearingPrincipal the principal that bears the line's ordinary interest at D: the principal
     *                         minus the installments of interest-free deferred purchases not yet due. The
     *                         ordinary interest basis; null in snapshots taken before it existed
     * @param scheduledInterest the interest the plans set for the periods falling due on D (zero when none, or when
     *                         no principal is left): what a product that charges interest per period is charged on
     *                         D. Null in snapshots taken before it existed
     * @param statementDueUnpaid what a statement-billed account's statements asked to pay on D itself and D's end left
     *                         unpaid: not late on D, late from D+1. A statement cut on D is issued once D is over, so its
     *                         arrears are the overdue principal plus this
     * @param scheduledTax     the IVA the plans set for the periods falling due on D, each installment's rounded as
     *                         its client pays it: what a per-period product is charged as IVA on D. Null in snapshots
     *                         taken before it existed
     */
    record AccountSnapshot(LocalDate businessDate, UUID creditAccountId, String accountStatus,
                           BigDecimal principal, BigDecimal accruedInterest, BigDecimal iva, BigDecimal penalty,
                           BigDecimal totalDebt, BigDecimal overduePrincipal, int overdueInstallments,
                           LocalDate oldestDueDate, int daysDelinquent, long balanceVersion, int laterMovements,
                           Instant takenAt, BigDecimal ledgerTotal, BigDecimal ledgerGap,
                           BigDecimal interestBearingPrincipal, BigDecimal scheduledInterest,
                           BigDecimal statementDueUnpaid, BigDecimal scheduledTax) {}
}
