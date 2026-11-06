package com.fintech.payments.domain.allocation;

import java.math.BigDecimal;
import java.util.List;

/** Where every peso of a received credit goes. The lines always add up to the credit. */
public record AllocationPlan(List<AllocationLine> lines, ReferenceStatus referenceStatus) {

    public AllocationPlan {
        lines = List.copyOf(lines);
    }

    public BigDecimal toLoans() {
        return lines.stream().filter(AllocationLine::toLoan).map(AllocationLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal toCreditBalance() {
        return lines.stream().filter(l -> !l.toLoan()).map(AllocationLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal total() {
        return toLoans().add(toCreditBalance());
    }
}
