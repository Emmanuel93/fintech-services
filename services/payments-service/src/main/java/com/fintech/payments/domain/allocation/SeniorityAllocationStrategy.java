package com.fintech.payments.domain.allocation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The prelation of a received credit among the payer's loans:
 * <ol>
 *   <li>The loan the transfer's numeric reference names, if it is the payer's and owes: up to its debt
 *       ({@link AllocationMode#REFERENCED}).</li>
 *   <li>Then the rest of the payer's loans with debt, the oldest first (activation date, then id for a stable order),
 *       each up to its debt ({@link AllocationMode#BY_RULE}). A distributor line is one loan: within it, the portfolio
 *       pays the oldest placement first.</li>
 *   <li>What no loan owed stays as the payer's balance in favor ({@link AllocationMode#CREDIT_BALANCE}).</li>
 * </ol>
 * Never more than a loan owes: the portfolio rejects an overpayment, and money in the bank has to land somewhere.
 */
public final class SeniorityAllocationStrategy implements AllocationStrategy {

    private static final Comparator<LoanDebt> OLDEST_FIRST = Comparator
            .comparing(LoanDebt::activatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(l -> l.creditAccountId().toString());

    @Override
    public AllocationPlan allocate(BigDecimal amount, Long numericReference, List<LoanDebt> loans) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Un abono sin importe no se aplica: " + amount);
        }
        BigDecimal remaining = amount.setScale(2, RoundingMode.HALF_UP);
        List<AllocationLine> lines = new ArrayList<>();

        Optional<LoanDebt> referenced = numericReference == null ? Optional.empty()
                : loans.stream().filter(l -> numericReference.equals(l.paymentReference())).findFirst();
        ReferenceStatus status = numericReference == null ? ReferenceStatus.ABSENT
                : referenced.isPresent() ? ReferenceStatus.MATCHED : ReferenceStatus.NOT_FOUND;

        if (referenced.isPresent() && referenced.get().owes()) {
            BigDecimal part = remaining.min(referenced.get().debt());
            lines.add(new AllocationLine(lines.size() + 1, referenced.get().creditAccountId(), part, AllocationMode.REFERENCED));
            remaining = remaining.subtract(part);
        }

        List<LoanDebt> bySeniority = loans.stream()
                .filter(LoanDebt::owes)
                .filter(l -> referenced.isEmpty() || !l.creditAccountId().equals(referenced.get().creditAccountId()))
                .sorted(OLDEST_FIRST)
                .toList();
        for (LoanDebt loan : bySeniority) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal part = remaining.min(loan.debt());
            lines.add(new AllocationLine(lines.size() + 1, loan.creditAccountId(), part, AllocationMode.BY_RULE));
            remaining = remaining.subtract(part);
        }

        if (remaining.signum() > 0) {
            lines.add(new AllocationLine(lines.size() + 1, null, remaining, AllocationMode.CREDIT_BALANCE));
        }
        return new AllocationPlan(lines, status);
    }
}
