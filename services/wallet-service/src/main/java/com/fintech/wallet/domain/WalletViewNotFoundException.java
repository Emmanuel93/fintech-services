package com.fintech.wallet.domain;

import com.fintech.shared.exception.DomainException;

public class WalletViewNotFoundException extends DomainException {
    public WalletViewNotFoundException(String creditAccountId) {
        super("WALLET_NOT_FOUND", "WalletView not found for creditAccountId: " + creditAccountId);
    }
}
