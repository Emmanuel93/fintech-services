package com.fintech.payments.domain;

import com.fintech.shared.exception.DomainException;

public class InvalidPaymentStateException extends DomainException {
    public InvalidPaymentStateException(String message) {
        super("PAYMENTS_INVALID_STATE", message);
    }
}
