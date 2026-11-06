package com.fintech.payments.domain.balance;

/** Why a payer's balance in favor changed; the sign of the movement follows from it. */
public enum BalanceMovementType {
    /** Part of a received credit that no loan owed (+). */
    EXCESS_FROM_CREDIT(true),
    /** A loan rejected its share of a received credit; the money is still in the bank (+). */
    REJECTED_ALLOCATION(true),
    /** Applied to a due installment of the payer (−). */
    APPLIED_TO_LOAN(false),
    /** The loan rejected an application of the balance; it comes back (+). */
    APPLICATION_REJECTED(true),
    /** Sent back to the payer (−). */
    REFUND(false);

    private final boolean increases;

    BalanceMovementType(boolean increases) {
        this.increases = increases;
    }

    public boolean increases() {
        return increases;
    }
}
