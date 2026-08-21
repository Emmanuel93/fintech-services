package com.fintech.wallet.domain;

import com.fintech.shared.exception.DomainException;
import java.math.BigDecimal;

public class InsufficientCreditException extends DomainException {
    public InsufficientCreditException(BigDecimal requested, BigDecimal available) {
        super("WALLET_INSUFFICIENT_CREDIT",
                "Requested amount " + requested + " exceeds available credit " + available);
    }
}
