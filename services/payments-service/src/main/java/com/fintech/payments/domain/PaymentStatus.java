package com.fintech.payments.domain;

public enum PaymentStatus {
    PENDING,    // received, pre-validated against snapshot, awaiting credit-portfolio confirmation
    CONFIRMED,  // credit-portfolio applied the payment and published balance-updated
    REJECTED,   // credit-portfolio rejected (overpayment) or pre-validation failed
    REVERSED    // reversed after confirmation (e.g., SPEI devolution)
}
