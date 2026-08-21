package com.fintech.origination.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body for POST /applications/{id}/approve and POST /applications/{id}/reject.
 *
 * @param decidedBy       userId of the underwriter or "COMMITTEE"
 * @param approved        true = APPROVED, false = REJECTED
 * @param rejectionReason mandatory when approved=false (UW-05 / CONDUSEF)
 */
public record RecordManualDecisionRequest(
        @NotBlank String decidedBy,
        @NotNull Boolean approved,
        String rejectionReason
) {}
