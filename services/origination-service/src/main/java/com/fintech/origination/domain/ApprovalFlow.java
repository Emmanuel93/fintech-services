package com.fintech.origination.domain;

/**
 * Determines who makes the final credit decision.
 * Assigned in {@link CreditApplication} when the scoring decision arrives.
 *
 * <ul>
 *   <li>{@code AUTOMATIC} — score ≥ auto-approval threshold: system decides immediately.</li>
 *   <li>{@code MANUAL}    — score in grey zone: assigned underwriter decides.</li>
 *   <li>{@code COMMITTEE} — high amount, high risk (tier≥4), or PEP flag: committee decides.</li>
 * </ul>
 */
public enum ApprovalFlow {
    AUTOMATIC,
    MANUAL,
    COMMITTEE
}
