package com.fintech.wallet.domain;

import com.fintech.shared.exception.DomainException;

public class PaymentInstructionNotFoundException extends DomainException {
    public PaymentInstructionNotFoundException(String instructionId) {
        super("WALLET_INSTRUCTION_NOT_FOUND", "PaymentInstruction not found: " + instructionId);
    }
}
