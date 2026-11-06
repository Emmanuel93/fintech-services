package com.fintech.charges.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The portfolio's per-account day snapshot, as charges sees it: the preliminary close of D that every
 * accrual of D is based on. The portfolio owns it; charges asks for it, copies what it needs and plans
 * the day from the copy.
 */
public interface PortfolioDaySnapshots {

    /** Asks the portfolio to take the snapshot of D, or to return it if it already exists. */
    Status take(LocalDate businessDate);

    /** One page of the snapshot's accounts, ordered by account id, starting after {@code after}. */
    List<AccountSnapshot> page(LocalDate businessDate, UUID after, int size);

    /**
     * @param taken   the snapshot exists; otherwise {@code detail} says what the portfolio is waiting for
     * @param accounts how many accounts it holds, when taken
     */
    record Status(boolean taken, String detail, int accounts) {}

    /**
     * @param interestBearingPrincipal the principal that bears the ordinary interest: without the installments of
     *                                 interest-free deferred purchases not yet due. Null when the portfolio did not
     *                                 send it; the basis is then the principal
     * @param scheduledInterest        the interest the plans set for the periods falling due that day: what an
     *                                 account that accrues per period is charged. Null when the portfolio did not send it
     * @param scheduledTax             the IVA of those installments, each rounded as its client pays it: the per-period
     *                                 account's IVA of the day. Null when the portfolio did not send it
     */
    record AccountSnapshot(UUID creditAccountId, BigDecimal principal, BigDecimal overduePrincipal,
                           LocalDate oldestDueDate, int daysDelinquent, long balanceVersion, Instant takenAt,
                           BigDecimal interestBearingPrincipal, BigDecimal scheduledInterest, BigDecimal scheduledTax) {

        public AccountSnapshot(UUID creditAccountId, BigDecimal principal, BigDecimal overduePrincipal,
                               LocalDate oldestDueDate, int daysDelinquent, long balanceVersion, Instant takenAt,
                               BigDecimal interestBearingPrincipal, BigDecimal scheduledInterest) {
            this(creditAccountId, principal, overduePrincipal, oldestDueDate, daysDelinquent, balanceVersion, takenAt,
                    interestBearingPrincipal, scheduledInterest, null);
        }

        public AccountSnapshot(UUID creditAccountId, BigDecimal principal, BigDecimal overduePrincipal,
                               LocalDate oldestDueDate, int daysDelinquent, long balanceVersion, Instant takenAt,
                               BigDecimal interestBearingPrincipal) {
            this(creditAccountId, principal, overduePrincipal, oldestDueDate, daysDelinquent, balanceVersion, takenAt,
                    interestBearingPrincipal, null);
        }

        public AccountSnapshot(UUID creditAccountId, BigDecimal principal, BigDecimal overduePrincipal,
                               LocalDate oldestDueDate, int daysDelinquent, long balanceVersion, Instant takenAt) {
            this(creditAccountId, principal, overduePrincipal, oldestDueDate, daysDelinquent, balanceVersion, takenAt, principal);
        }

        /** The ordinary interest basis of the day. */
        public BigDecimal interestBasis() {
            return interestBearingPrincipal != null ? interestBearingPrincipal : principal;
        }
    }
}
