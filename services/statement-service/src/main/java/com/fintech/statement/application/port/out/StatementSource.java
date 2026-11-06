package com.fintech.statement.application.port.out;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * De dónde salen los datos del documento: la vista del período en cartera y los nombres en party.
 *
 * <p>Los récords son la copia local del contrato de {@code GET /accounts/{id}/statement-view}: lo
 * que no se conoce se ignora, como en cualquier consumidor de la plataforma.
 */
public interface StatementSource {

    SourceView view(UUID creditAccountId, LocalDate from, LocalDate to, LocalDate dueUntil);

    /** Nombre y RFC por party. Los que no existan simplemente no vienen. */
    Map<UUID, PartySummary> parties(Collection<UUID> partyIds);

    /**
     * What a distributor earns on her line: the share of the interest that applies to her and what she earned payment
     * by payment. Commission-service owns it; the statement shows it next to what she pays.
     */
    DistributorCommission commission(UUID creditAccountId);

    record PartySummary(UUID partyId, String displayName, String rfc) {}

    /**
     * @param rate   the share of the interest collected she earns (0.18); null when no policy applies to the line
     * @param earned the commissions accrued on the line's payments, each dated on its payment's business day
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DistributorCommission(BigDecimal rate, List<EarnedCommission> earned) {
        public static final DistributorCommission NONE = new DistributorCommission(null, List.of());

        public DistributorCommission {
            earned = earned == null ? List.of() : List.copyOf(earned);
        }
    }

    /**
     * One payment's commission.
     *
     * @param valueDate the business day of the payment it was earned on
     * @param basis     the interest that payment collected
     * @param amount    the commission (basis × rate), before IVA and withholdings
     * @param iva       the IVA on the commission
     * @param status    ACCRUED (to be paid out), LIQUIDATED (in a lot), REVERSED
     * @param period    the month it is paid out with, YYYYMM
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EarnedCommission(LocalDate valueDate, BigDecimal basis, BigDecimal rate, BigDecimal amount, BigDecimal iva,
                            String status, String period,
                            /** {@code NETTED}: deducted from what she delivered against a statement; null: paid out in a lot. */
                            String settlement,
                            /** A netted commission: the statement it was netted against, its due date and how late she paid. */
                            Integer cycleNumber, LocalDate dueDate, Integer daysLate) {

        public EarnedCommission(LocalDate valueDate, BigDecimal basis, BigDecimal rate, BigDecimal amount, BigDecimal iva,
                                String status, String period) {
            this(valueDate, basis, rate, amount, iva, status, period, null, null, null, null);
        }

        public boolean netted() { return "NETTED".equalsIgnoreCase(settlement); }
    }

    /**
     * One step of the distributor's ladder on a statement, as the portfolio sets it: what she keeps of what her clients
     * paid it if she delivers {@code daysLate} days after its due date (0: on time; the last step earns nothing).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LadderStep(int daysLate, BigDecimal rate, BigDecimal commission) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SourceView(Account account, LocalDate from, LocalDate to, LocalDate dueUntil,
                      Balances opening, Balances closing, Totals totals, List<Movement> movements,
                      List<InstallmentLine> installments, List<PlacementLine> placements,
                      BigDecimal plansOutstanding, BigDecimal plansDue, BigDecimal plansPrincipalDue,
                      BigDecimal periodCharges, BigDecimal arrears, List<PlacementLine> purchasePlans,
                      /** The distributor's ladder on this statement; empty or null when the product pays no distributor. */
                      List<LadderStep> commissionLadder,
                      /** {@code NETTED} or {@code PAYOUT}; null when the product pays no distributor. */
                      String commissionSettlement,
                      /** The day a quita closed the line to new placements (her exit); null when it is open or from an older view. */
                      LocalDate lineClosedOn) {

        public SourceView(Account account, LocalDate from, LocalDate to, LocalDate dueUntil,
                          Balances opening, Balances closing, Totals totals, List<Movement> movements,
                          List<InstallmentLine> installments, List<PlacementLine> placements,
                          BigDecimal plansOutstanding, BigDecimal plansDue, BigDecimal plansPrincipalDue,
                          BigDecimal periodCharges, BigDecimal arrears, List<PlacementLine> purchasePlans,
                          List<LadderStep> commissionLadder, String commissionSettlement) {
            this(account, from, to, dueUntil, opening, closing, totals, movements, installments, placements,
                    plansOutstanding, plansDue, plansPrincipalDue, periodCharges, arrears, purchasePlans, commissionLadder,
                    commissionSettlement, null);
        }

        public SourceView(Account account, LocalDate from, LocalDate to, LocalDate dueUntil,
                          Balances opening, Balances closing, Totals totals, List<Movement> movements,
                          List<InstallmentLine> installments, List<PlacementLine> placements,
                          BigDecimal plansOutstanding, BigDecimal plansDue, BigDecimal plansPrincipalDue,
                          BigDecimal periodCharges, BigDecimal arrears, List<PlacementLine> purchasePlans) {
            this(account, from, to, dueUntil, opening, closing, totals, movements, installments, placements,
                    plansOutstanding, plansDue, plansPrincipalDue, periodCharges, arrears, purchasePlans, List.of(), null, null);
        }

        /** Her commission is deducted from what she delivers (the market's way). */
        public boolean commissionNetted() { return "NETTED".equalsIgnoreCase(commissionSettlement); }

        public List<LadderStep> ladder() { return commissionLadder == null ? List.of() : commissionLadder; }

        public SourceView(Account account, LocalDate from, LocalDate to, LocalDate dueUntil,
                          Balances opening, Balances closing, Totals totals, List<Movement> movements,
                          List<InstallmentLine> installments, List<PlacementLine> placements) {
            this(account, from, to, dueUntil, opening, closing, totals, movements, installments, placements,
                    null, null, null, null, null, List.of());
        }

        public SourceView(Account account, LocalDate from, LocalDate to, LocalDate dueUntil,
                          Balances opening, Balances closing, Totals totals, List<Movement> movements,
                          List<InstallmentLine> installments, List<PlacementLine> placements,
                          BigDecimal plansOutstanding, BigDecimal plansDue, BigDecimal plansPrincipalDue,
                          BigDecimal periodCharges, BigDecimal arrears) {
            this(account, from, to, dueUntil, opening, closing, totals, movements, installments, placements,
                    plansOutstanding, plansDue, plansPrincipalDue, periodCharges, arrears, List.of());
        }
    }

    /**
     * {@code clabe} is the person's collection CLABE (portfolio's {@code collectionClabe}) and {@code paymentReference}
     * the numeric reference that names this loan in a SPEI transfer: together they are how the statement tells the
     * client to pay.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Account(UUID creditAccountId, String contractNumber, String productCode, String productType,
                   String productBehavior, UUID obligorPartyId, String status, BigDecimal nominalRate,
                   BigDecimal moratoriumRate, BigDecimal vatRate, BigDecimal creditLimit,
                   BigDecimal availableCredit, BigDecimal principalBalance, BigDecimal interestBalance,
                   BigDecimal penaltyBalance, BigDecimal ivaBalance, Integer assignedTerm,
                   String amortizationType, Instant activatedAt,
                   @JsonAlias("collectionClabe") String clabe, int daysDelinquent,
                   BigDecimal cat, Long paymentReference) {

        /** Without the payment reference (accounts before it existed). */
        public Account(UUID creditAccountId, String contractNumber, String productCode, String productType,
                       String productBehavior, UUID obligorPartyId, String status, BigDecimal nominalRate,
                       BigDecimal moratoriumRate, BigDecimal vatRate, BigDecimal creditLimit,
                       BigDecimal availableCredit, BigDecimal principalBalance, BigDecimal interestBalance,
                       BigDecimal penaltyBalance, BigDecimal ivaBalance, Integer assignedTerm,
                       String amortizationType, Instant activatedAt, String clabe, int daysDelinquent,
                       BigDecimal cat) {
            this(creditAccountId, contractNumber, productCode, productType, productBehavior, obligorPartyId, status,
                    nominalRate, moratoriumRate, vatRate, creditLimit, availableCredit, principalBalance,
                    interestBalance, penaltyBalance, ivaBalance, assignedTerm, amortizationType, activatedAt, clabe,
                    daysDelinquent, cat, null);
        }

        /** Without the contracted CAT (accounts opened before it was kept). */
        public Account(UUID creditAccountId, String contractNumber, String productCode, String productType,
                       String productBehavior, UUID obligorPartyId, String status, BigDecimal nominalRate,
                       BigDecimal moratoriumRate, BigDecimal vatRate, BigDecimal creditLimit,
                       BigDecimal availableCredit, BigDecimal principalBalance, BigDecimal interestBalance,
                       BigDecimal penaltyBalance, BigDecimal ivaBalance, Integer assignedTerm,
                       String amortizationType, Instant activatedAt, String clabe, int daysDelinquent) {
            this(creditAccountId, contractNumber, productCode, productType, productBehavior, obligorPartyId, status,
                    nominalRate, moratoriumRate, vatRate, creditLimit, availableCredit, principalBalance,
                    interestBalance, penaltyBalance, ivaBalance, assignedTerm, amortizationType, activatedAt, clabe,
                    daysDelinquent, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Balances(BigDecimal principal, BigDecimal interest, BigDecimal penalty, BigDecimal total) {}

    /** @param commissionNetted the distributor's commission deducted from her deliveries in the period; null from an older view */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Totals(BigDecimal dispositions, BigDecimal payments, BigDecimal ordinaryInterest,
                  BigDecimal moratoriumInterest, BigDecimal fees, BigDecimal iva, BigDecimal writeOffs,
                  BigDecimal reversals, BigDecimal commissionNetted) {

        public Totals(BigDecimal dispositions, BigDecimal payments, BigDecimal ordinaryInterest,
                      BigDecimal moratoriumInterest, BigDecimal fees, BigDecimal iva, BigDecimal writeOffs,
                      BigDecimal reversals) {
            this(dispositions, payments, ordinaryInterest, moratoriumInterest, fees, iva, writeOffs, reversals, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Movement(Instant appliedAt, String type, BigDecimal amount, BigDecimal principalAfter) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    /** @param originalDueDate the date it had before it moved (to a business day, or by a skip); null if it never did */
    record InstallmentLine(UUID scheduleId, int number, LocalDate dueDate, BigDecimal principal,
                           BigDecimal interest, BigDecimal tax, BigDecimal total, String status,
                           LocalDate originalDueDate,
                           /** The distributor's early-payment discount on this installment (her flat commission); null from an older view. */
                           BigDecimal commission) {

        public InstallmentLine(UUID scheduleId, int number, LocalDate dueDate, BigDecimal principal,
                               BigDecimal interest, BigDecimal tax, BigDecimal total, String status,
                               LocalDate originalDueDate) {
            this(scheduleId, number, dueDate, principal, interest, tax, total, status, originalDueDate, null);
        }

        public InstallmentLine(UUID scheduleId, int number, LocalDate dueDate, BigDecimal principal,
                               BigDecimal interest, BigDecimal tax, BigDecimal total, String status) {
            this(scheduleId, number, dueDate, principal, interest, tax, total, status, null, null);
        }

        /** It moved from the date the plan set (a non-business day, or a skipped payment). */
        public boolean moved() {
            return originalDueDate != null && !originalDueDate.equals(dueDate);
        }
    }

    /**
     * A disposition with its own plan: a distributor's placement or a card's purchase in installments.
     *
     * @param overdueInstallments installments billed by earlier statements and still unpaid at the cutoff
     * @param oldestOverdueDue    the due date of the oldest of them; null when none
     * @param paidOffInPeriod     settled in this period (listed once as settled, then no more)
     * @param interestFree        a purchase at months without interest
     * @param settledOn           the day it was fully paid; null while it is not, or when the portfolio did not keep it
     * @param lastDueDate         the due date of its last installment as planned
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlacementLine(UUID dispositionId, UUID beneficiaryPartyId, BigDecimal amount, Instant placedAt,
                         int totalInstallments, int paidInstallments, BigDecimal installmentAmount,
                         BigDecimal outstandingPrincipal, LocalDate nextDueDate, List<InstallmentLine> dueInPeriod,
                         int overdueInstallments, LocalDate oldestOverdueDue, boolean paidOffInPeriod,
                         boolean interestFree, LocalDate settledOn, LocalDate lastDueDate,
                         /** The plan's CAT (Circular 21/2009), a fraction: 1.538762 is 153.9 %. Null from an older view. */
                         BigDecimal cat,
                         /** Her flat commission on the installment this statement bills of it, delivered on time; null when none. */
                         BigDecimal commissionPerPayment,
                         /** What the plan's whole commission is of its whole interest (0.86 at 12 fortnights). */
                         BigDecimal commissionShareOfInterest,
                         BigDecimal commissionTotal,
                         BigDecimal interestTotal) {

        public PlacementLine(UUID dispositionId, UUID beneficiaryPartyId, BigDecimal amount, Instant placedAt,
                             int totalInstallments, int paidInstallments, BigDecimal installmentAmount,
                             BigDecimal outstandingPrincipal, LocalDate nextDueDate, List<InstallmentLine> dueInPeriod,
                             int overdueInstallments, LocalDate oldestOverdueDue, boolean paidOffInPeriod,
                             boolean interestFree, LocalDate settledOn, LocalDate lastDueDate, BigDecimal cat) {
            this(dispositionId, beneficiaryPartyId, amount, placedAt, totalInstallments, paidInstallments,
                    installmentAmount, outstandingPrincipal, nextDueDate, dueInPeriod, overdueInstallments,
                    oldestOverdueDue, paidOffInPeriod, interestFree, settledOn, lastDueDate, cat, null, null, null, null);
        }

        public PlacementLine(UUID dispositionId, UUID beneficiaryPartyId, BigDecimal amount, Instant placedAt,
                             int totalInstallments, int paidInstallments, BigDecimal installmentAmount,
                             BigDecimal outstandingPrincipal, LocalDate nextDueDate, List<InstallmentLine> dueInPeriod,
                             int overdueInstallments, LocalDate oldestOverdueDue, boolean paidOffInPeriod,
                             boolean interestFree, LocalDate settledOn, LocalDate lastDueDate) {
            this(dispositionId, beneficiaryPartyId, amount, placedAt, totalInstallments, paidInstallments,
                    installmentAmount, outstandingPrincipal, nextDueDate, dueInPeriod, overdueInstallments,
                    oldestOverdueDue, paidOffInPeriod, interestFree, settledOn, lastDueDate, null);
        }

        public PlacementLine(UUID dispositionId, UUID beneficiaryPartyId, BigDecimal amount, Instant placedAt,
                             int totalInstallments, int paidInstallments, BigDecimal installmentAmount,
                             BigDecimal outstandingPrincipal, LocalDate nextDueDate, List<InstallmentLine> dueInPeriod,
                             int overdueInstallments, LocalDate oldestOverdueDue, boolean paidOffInPeriod,
                             boolean interestFree) {
            this(dispositionId, beneficiaryPartyId, amount, placedAt, totalInstallments, paidInstallments,
                    installmentAmount, outstandingPrincipal, nextDueDate, dueInPeriod, overdueInstallments,
                    oldestOverdueDue, paidOffInPeriod, interestFree, null, null);
        }

        public PlacementLine(UUID dispositionId, UUID beneficiaryPartyId, BigDecimal amount, Instant placedAt,
                             int totalInstallments, int paidInstallments, BigDecimal installmentAmount,
                             BigDecimal outstandingPrincipal, LocalDate nextDueDate, List<InstallmentLine> dueInPeriod) {
            this(dispositionId, beneficiaryPartyId, amount, placedAt, totalInstallments, paidInstallments,
                    installmentAmount, outstandingPrincipal, nextDueDate, dueInPeriod, 0, null, false, false);
        }

        /** Settled in the period: listed apart from the live ones, never under "vigentes". */
        public boolean settledInPeriod() {
            return paidOffInPeriod && (outstandingPrincipal == null || outstandingPrincipal.signum() == 0);
        }

        /** Paid off before its last installment fell due. */
        public boolean paidAhead() {
            return settledOn != null && lastDueDate != null && settledOn.isBefore(lastDueDate);
        }
    }
}
