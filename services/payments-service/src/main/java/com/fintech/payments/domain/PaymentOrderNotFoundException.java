package com.fintech.payments.domain;

import com.fintech.shared.exception.DomainException;

public class PaymentOrderNotFoundException extends DomainException {
    public PaymentOrderNotFoundException(String id) {
        super("PAYMENTS_ORDER_NOT_FOUND", "PaymentOrder not found: " + id);
    }
}
