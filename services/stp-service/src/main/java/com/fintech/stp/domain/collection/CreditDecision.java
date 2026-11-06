package com.fintech.stp.domain.collection;

/** What the connector answers STP for a credit while STP waits: keep it, or send it back to the payer. */
public enum CreditDecision {
    CONFIRMED, RETURNED
}
