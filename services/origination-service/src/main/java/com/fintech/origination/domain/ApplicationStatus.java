package com.fintech.origination.domain;

import java.util.Set;

/**
 * Full lifecycle of a CreditApplication (ADR-001 Phases A–G).
 *
 * <pre>
 * DRAFT → PENDING_SCORING → SCORING
 *                              ├─ AUTO_APPROVED → APPROVED
 *                              ├─ MANUAL_REVIEW → UNDER_MANUAL_REVIEW → APPROVED|REJECTED|PENDING_DOCUMENTS
 *                              ├─ REJECTED      → REJECTED  (terminal)
 *                              └─ failure       → FAILED    (terminal)
 *
 * APPROVED → OFFER_PRESENTED → OFFER_ACCEPTED → PENDING_SIGNATURE → CONTRACT_SIGNED → DISBURSED (terminal)
 *                │                  │
 *                └─ OFFER_REJECTED  └─ OFFER_REJECTED  (terminal)
 *                └─ OFFER_EXPIRED                       (terminal, TTL)
 *
 * CANCELLED (any non-terminal state)
 * </pre>
 *
 * APPROVED is non-terminal: the flow continues to offer → contract → disbursement.
 */
public enum ApplicationStatus {
    DRAFT,
    PENDING_SCORING,
    SCORING,
    UNDER_MANUAL_REVIEW,
    COMMITTEE_REVIEW,
    PENDING_DOCUMENTS,
    APPROVED,
    OFFER_PRESENTED,
    OFFER_ACCEPTED,
    OFFER_EXPIRED,
    OFFER_REJECTED,
    PENDING_SIGNATURE,
    CONTRACT_SIGNED,
    DISBURSED,
    REJECTED,
    FAILED,
    CANCELLED;

    private static final Set<ApplicationStatus> TERMINAL =
            Set.of(REJECTED, FAILED, CANCELLED, OFFER_REJECTED, OFFER_EXPIRED, DISBURSED);

    /** Statuses where a human decision (approve/reject) is expected. */
    public boolean awaitingHumanDecision() {
        return this == UNDER_MANUAL_REVIEW || this == COMMITTEE_REVIEW;
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
