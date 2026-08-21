package com.fintech.origination.application;

import java.util.UUID;

/**
 * Command for underwriter or committee to record a manual credit decision.
 *
 * @param applicationId   target application
 * @param decidedBy       userId of the underwriter or "COMMITTEE"
 * @param approved        true = APPROVED, false = REJECTED
 * @param rejectionReason mandatory when approved=false (UW-05 / CONDUSEF)
 */
public record RecordApprovalDecisionCommand(
        UUID applicationId,
        String decidedBy,
        boolean approved,
        String rejectionReason
) {}
