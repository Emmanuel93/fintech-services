package com.fintech.payments.domain.allocation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One loan of the payer as it stands when a credit arrives: what it owes, how old it is and its payment reference. */
public record LoanDebt(UUID creditAccountId, Long paymentReference, Instant activatedAt, BigDecimal debt) {

    public LoanDebt {
        Objects.requireNonNull(creditAccountId, "creditAccountId");
        debt = debt == null ? BigDecimal.ZERO : debt;
    }

    public boolean owes() {
        return debt.signum() > 0;
    }
}
