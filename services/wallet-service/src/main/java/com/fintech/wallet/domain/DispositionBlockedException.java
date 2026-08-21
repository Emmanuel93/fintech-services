package com.fintech.wallet.domain;

import com.fintech.shared.exception.DomainException;

public class DispositionBlockedException extends DomainException {
    public DispositionBlockedException(String reason) {
        super("WALLET_DISPOSITION_BLOCKED", "Disposition blocked: " + reason);
    }
}
