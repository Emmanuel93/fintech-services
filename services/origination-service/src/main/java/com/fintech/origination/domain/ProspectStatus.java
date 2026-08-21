package com.fintech.origination.domain;

public enum ProspectStatus {
    /** Initial state — data captured, pending downstream processing. */
    CAPTURED,
    /** Party domain has acknowledged and started KYC flow. */
    SUBMITTED,
    /** Prospect converted to an active Party and started a credit application. */
    CONVERTED,
    /** Prospect record expired without conversion (TTL elapsed). */
    EXPIRED
}
