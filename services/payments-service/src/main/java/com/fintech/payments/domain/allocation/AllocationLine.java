package com.fintech.payments.domain.allocation;

import java.math.BigDecimal;
import java.util.UUID;

/** One part of a received credit: to a loan, or (with no loan) to the payer's balance in favor. */
public record AllocationLine(int sequence, UUID creditAccountId, BigDecimal amount, AllocationMode mode,
                             UUID beneficiaryPartyId) {

    public AllocationLine(int sequence, UUID creditAccountId, BigDecimal amount, AllocationMode mode) {
        this(sequence, creditAccountId, amount, mode, null);
    }


    public boolean toLoan() {
        return creditAccountId != null;
    }
}
