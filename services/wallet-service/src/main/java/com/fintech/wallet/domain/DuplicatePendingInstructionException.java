package com.fintech.wallet.domain;

import com.fintech.shared.exception.DomainException;

public class DuplicatePendingInstructionException extends DomainException {
    public DuplicatePendingInstructionException(String creditAccountId, String paymentMethod) {
        super("WALLET_DUPLICATE_INSTRUCTION",
                "A PENDING instruction already exists for creditAccountId=" + creditAccountId
                        + " paymentMethod=" + paymentMethod);
    }
}
