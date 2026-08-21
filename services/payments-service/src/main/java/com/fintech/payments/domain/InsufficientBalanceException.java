package com.fintech.payments.domain;

import com.fintech.shared.exception.DomainException;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientBalanceException extends DomainException {
    public InsufficientBalanceException(UUID creditAccountId, BigDecimal requested, BigDecimal available) {
        super("PAYMENTS_INSUFFICIENT_BALANCE",
                "Payment amount " + requested + " exceeds totalDebt " + available
                        + " for account " + creditAccountId);
    }
}
