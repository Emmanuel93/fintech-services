package com.fintech.creditportfolio.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * La vista de la que se arma el estado de cuenta de un período.
 *
 * <p>Es una lectura, no una copia: la cuenta, su libro entre dos fechas, su plan y —en una línea de
 * distribuidora— cada colocación con lo que le toca pagar. Cartera es dueña de todos esos datos;
 * el servicio de estados de cuenta los pide aquí en vez de leer tablas ajenas, y guarda la foto que
 * usó junto al documento.
 */
public interface StatementViewUseCase {

    /**
     * @param from      primer día del período (inclusive, día de negocio)
     * @param to        día del corte (inclusive)
     * @param dueUntil  hasta qué fecha cuentan las cuotas «a pagar en este período»; nulo = {@code to}
     */
    StatementView view(UUID creditAccountId, LocalDate from, LocalDate to, LocalDate dueUntil);

    /**
     * @param plansOutstanding  what the account's plans (placements, deferred and amortized purchases) still owe
     * @param plansDue          what this statement bills of them (totals of the installments it bills)
     * @param plansPrincipalDue the principal of those same installments
     * @param periodCharges     interest, IVA, moratorium and fees charged in the period, net of reversals
     * @param arrears           what earlier statements asked for and is still unpaid at the cutoff
     */
    record StatementView(Account account, LocalDate from, LocalDate to, LocalDate dueUntil,
                         Balances opening, Balances closing, Totals totals, List<Movement> movements,
                         List<InstallmentLine> installments, List<PlacementLine> placements,
                         BigDecimal plansOutstanding, BigDecimal plansDue, BigDecimal plansPrincipalDue,
                         BigDecimal periodCharges, BigDecimal arrears, List<PlacementLine> purchasePlans,
                         /** The distributor's ladder on this statement; empty when the product pays no distributor. */
                         List<LadderStep> commissionLadder,
                         /** {@code NETTED} or {@code PAYOUT}; null when the product pays no distributor. */
                         String commissionSettlement,
                         /** The day a quita closed the line to new placements (her exit); null when it is open. */
                         LocalDate lineClosedOn) {

        public StatementView(Account account, LocalDate from, LocalDate to, LocalDate dueUntil,
                             Balances opening, Balances closing, Totals totals, List<Movement> movements,
                             List<InstallmentLine> installments, List<PlacementLine> placements,
                             BigDecimal plansOutstanding, BigDecimal plansDue, BigDecimal plansPrincipalDue,
                             BigDecimal periodCharges, BigDecimal arrears, List<PlacementLine> purchasePlans,
                             List<LadderStep> commissionLadder, String commissionSettlement) {
            this(account, from, to, dueUntil, opening, closing, totals, movements, installments, placements,
                    plansOutstanding, plansDue, plansPrincipalDue, periodCharges, arrears, purchasePlans, commissionLadder,
                    commissionSettlement, null);
        }

        public StatementView(Account account, LocalDate from, LocalDate to, LocalDate dueUntil,
                             Balances opening, Balances closing, Totals totals, List<Movement> movements,
                             List<InstallmentLine> installments, List<PlacementLine> placements,
                             BigDecimal plansOutstanding, BigDecimal plansDue, BigDecimal plansPrincipalDue,
                             BigDecimal periodCharges, BigDecimal arrears, List<PlacementLine> purchasePlans) {
            this(account, from, to, dueUntil, opening, closing, totals, movements, installments, placements,
                    plansOutstanding, plansDue, plansPrincipalDue, periodCharges, arrears, purchasePlans, List.of(), null, null);
        }

        public StatementView(Account account, LocalDate from, LocalDate to, LocalDate dueUntil,
                             Balances opening, Balances closing, Totals totals, List<Movement> movements,
                             List<InstallmentLine> installments, List<PlacementLine> placements) {
            this(account, from, to, dueUntil, opening, closing, totals, movements, installments, placements,
                    null, null, null, null, null, List.of());
        }

        public StatementView(Account account, LocalDate from, LocalDate to, LocalDate dueUntil,
                             Balances opening, Balances closing, Totals totals, List<Movement> movements,
                             List<InstallmentLine> installments, List<PlacementLine> placements,
                             BigDecimal plansOutstanding, BigDecimal plansDue, BigDecimal plansPrincipalDue,
                             BigDecimal periodCharges, BigDecimal arrears) {
            this(account, from, to, dueUntil, opening, closing, totals, movements, installments, placements,
                    plansOutstanding, plansDue, plansPrincipalDue, periodCharges, arrears, List.of());
        }
    }

    record Account(UUID creditAccountId, String contractNumber, String productCode, String productType,
                   String productBehavior, UUID obligorPartyId, String status, BigDecimal nominalRate,
                   BigDecimal moratoriumRate, BigDecimal vatRate, BigDecimal creditLimit,
                   BigDecimal availableCredit, BigDecimal principalBalance, BigDecimal interestBalance,
                   BigDecimal penaltyBalance, BigDecimal ivaBalance, Integer assignedTerm,
                   String amortizationType, Instant activatedAt, String collectionClabe, int daysDelinquent,
                   BigDecimal cat) {}

    /** Capital, interés y moratorio según el libro. El IVA no lleva saldo propio en el libro. */
    record Balances(BigDecimal principal, BigDecimal interest, BigDecimal penalty, BigDecimal total) {}

    /** Lo que se movió en el período, por concepto. Los devengos diarios llegan aquí sumados. */
    /** @param commissionNetted the distributor's commission settled against her statements in the period, net of reversals */
    record Totals(BigDecimal dispositions, BigDecimal payments, BigDecimal ordinaryInterest,
                  BigDecimal moratoriumInterest, BigDecimal fees, BigDecimal iva, BigDecimal writeOffs,
                  BigDecimal reversals, BigDecimal commissionNetted) {

        public Totals(BigDecimal dispositions, BigDecimal payments, BigDecimal ordinaryInterest,
                      BigDecimal moratoriumInterest, BigDecimal fees, BigDecimal iva, BigDecimal writeOffs,
                      BigDecimal reversals) {
            this(dispositions, payments, ordinaryInterest, moratoriumInterest, fees, iva, writeOffs, reversals, BigDecimal.ZERO);
        }
    }

    /**
     * One step of the distributor's ladder on this statement: what she keeps of what her clients paid it if she
     * delivers {@code daysLate} days after its due date (0: on time; the last step is the first day that earns nothing).
     */
    record LadderStep(int daysLate, BigDecimal rate, BigDecimal commission) {}

    /** Un movimiento que se lista uno por uno: disposiciones, pagos, comisiones, castigos. */
    /** @param beneficiaryPartyId the distributor's client who made this payment himself; null for any other movement */
    record Movement(Instant appliedAt, String type, BigDecimal amount, BigDecimal principalAfter, UUID beneficiaryPartyId) {

        public Movement(Instant appliedAt, String type, BigDecimal amount, BigDecimal principalAfter) {
            this(appliedAt, type, amount, principalAfter, null);
        }
    }

    /** @param originalDueDate the date it had before it moved (to a business day, or by a skip); null if it never did */
    record InstallmentLine(UUID scheduleId, int number, LocalDate dueDate, BigDecimal principal,
                           BigDecimal interest, BigDecimal tax, BigDecimal total, String status,
                           LocalDate originalDueDate,
                           /** The distributor's flat commission on this installment when its client pays it; null when none. */
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
    }

    /**
     * A disposition with its own plan: a distributor's placement (to whom, how much, how it goes and what it bills
     * this period) or a card's purchase in installments.
     *
     * @param overdueInstallments installments billed by earlier statements and still unpaid at the cutoff
     * @param oldestOverdueDue    the due date of the oldest of them ("vencida desde"); null when none
     * @param paidOffInPeriod     fully paid within this period (by its last installment or ahead of it): the
     *                            statement reports it once as settled, and the following ones no longer list it
     * @param interestFree        a purchase at months without interest (its plan carries no interest)
     * @param settledOn           the day it was fully paid; null while it is not, or when it was paid before the day
     *                            was kept
     * @param lastDueDate         the due date of its last installment as planned (before any statement moved it)
     * @param cat                 the plan's CAT as Circular 21/2009 computes it (its principal and interest, without
     *                            IVA, over uniform periods), as a fraction: 1.538770 is 153.9 %; null without a plan
     */
    /**
     * @param commissionPerPayment      the distributor's commission on the installment this statement bills of it (or
     *                                  the next one), when she delivers on time; null when the product pays no distributor
     * @param commissionShareOfInterest what the plan's whole commission is of its whole interest (0.86 at 12 fortnights)
     * @param commissionTotal           the plan's whole commission, delivered on time every fortnight
     * @param interestTotal             the plan's whole interest
     */
    record PlacementLine(UUID dispositionId, UUID beneficiaryPartyId, BigDecimal amount, Instant placedAt,
                         int totalInstallments, int paidInstallments, BigDecimal installmentAmount,
                         BigDecimal outstandingPrincipal, LocalDate nextDueDate, List<InstallmentLine> dueInPeriod,
                         int overdueInstallments, LocalDate oldestOverdueDue, boolean paidOffInPeriod,
                         boolean interestFree, LocalDate settledOn, LocalDate lastDueDate, BigDecimal cat,
                         BigDecimal commissionPerPayment, BigDecimal commissionShareOfInterest,
                         BigDecimal commissionTotal, BigDecimal interestTotal) {

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
    }
}
