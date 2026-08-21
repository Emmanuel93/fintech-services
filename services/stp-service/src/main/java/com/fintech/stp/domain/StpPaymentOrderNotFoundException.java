package com.fintech.stp.domain;

import com.fintech.shared.exception.DomainException;

/** No existe la orden de pago consultada. */
public class StpPaymentOrderNotFoundException extends DomainException {

    public StpPaymentOrderNotFoundException(String message) {
        super("STP_PAYMENT_ORDER_NOT_FOUND", message);
    }
}
