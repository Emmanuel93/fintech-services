package com.fintech.payments.domain.allocation;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.Objects;
import java.util.ArrayList;
import java.math.RoundingMode;
import java.util.List;

/** Decides where a received credit goes among the payer's loans. Pure: the loans and the credit are the whole input. */
@FunctionalInterface
public interface AllocationStrategy {

    AllocationPlan allocate(BigDecimal amount, Long numericReference, List<LoanDebt> loans);

    /**
     * A distributor's client deposited to her CLABE with his own reference: the money goes to her line, up to what it
     * owes (the portfolio covers his installments first and the rest of her line with what is left); beyond her
     * line's debt it stays in her favor. It never pays another loan of hers: it is his payment for his loans with her.
     */
    default AllocationPlan allocateForClient(BigDecimal amount, LoanDebt line, UUID beneficiaryPartyId) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Un abono sin importe no se aplica: " + amount);
        }
        BigDecimal remaining = amount.setScale(2, RoundingMode.HALF_UP);
        List<AllocationLine> lines = new ArrayList<>();
        if (line.owes()) {
            BigDecimal part = remaining.min(line.debt());
            lines.add(new AllocationLine(1, line.creditAccountId(), part, AllocationMode.BENEFICIARY, beneficiaryPartyId));
            remaining = remaining.subtract(part);
        }
        if (remaining.signum() > 0) {
            lines.add(new AllocationLine(lines.size() + 1, null, remaining, AllocationMode.CREDIT_BALANCE));
        }
        return new AllocationPlan(lines, ReferenceStatus.MATCHED);
    }
}
