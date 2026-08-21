package com.fintech.commission.domain;

public enum CommissionType {
    /** B2B2C distributor — % of interest collected, accrued per payment (trailing, against collection). */
    DISTRIBUTOR_INTEREST_SHARE,
    /** Own-network (B2C) promoter — upfront, one time at activation. */
    ORIGINATION_FEE,
    /** Collections manager — per payment recovered on an assigned case. */
    COLLECTION_BONUS,
    /** Original promoter on a product reactivation/renewal. */
    RENEWAL_BONUS
}
