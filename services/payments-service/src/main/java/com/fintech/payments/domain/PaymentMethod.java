package com.fintech.payments.domain;

public enum PaymentMethod {
    SPEI,
    CODI,
    DOMICILIACION,
    /** Cash payment at a branch — cannot be reversed programmatically. */
    VENTANILLA,
    TARJETA,
    INTERNAL_TRANSFER;

    /** Whether this method supports programmatic reversal via the payments API. */
    public boolean allowsReversal() {
        return this != VENTANILLA;
    }
}
