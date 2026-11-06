package com.fintech.payments.domain.allocation;

import java.math.BigDecimal;
import java.util.List;

/** Decides where a received credit goes among the payer's loans. Pure: the loans and the credit are the whole input. */
@FunctionalInterface
public interface AllocationStrategy {

    AllocationPlan allocate(BigDecimal amount, Long numericReference, List<LoanDebt> loans);
}
