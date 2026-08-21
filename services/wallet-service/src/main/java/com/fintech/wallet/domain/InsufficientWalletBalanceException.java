package com.fintech.wallet.domain;

import com.fintech.shared.exception.DomainException;
import java.math.BigDecimal;

public class InsufficientWalletBalanceException extends DomainException {
    public InsufficientWalletBalanceException(BigDecimal requested, BigDecimal walletBalance) {
        super("WALLET_INSUFFICIENT_BALANCE",
                "Requested amount " + requested + " exceeds wallet balance " + walletBalance);
    }
}
