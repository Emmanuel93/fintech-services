package com.fintech.salesorg.infrastructure.adapter.in.api.dto;

import com.fintech.salesorg.domain.UnitAssignment;

import java.time.Instant;
import java.util.UUID;

public record UnitAssignmentResponse(
        UUID assignmentId,
        UUID unitId,
        String assigneeType,
        UUID assigneeId,
        String assignmentRole,
        String assignedBy,
        Instant assignedAt,
        Instant endedAt,
        boolean active) {

    public static UnitAssignmentResponse from(UnitAssignment a) {
        return new UnitAssignmentResponse(
                a.getAssignmentId(), a.getUnitId(),
                a.getAssigneeType() == null ? null : a.getAssigneeType().name(),
                a.getAssigneeId(), a.getAssignmentRole(), a.getAssignedBy(),
                a.getAssignedAt(), a.getEndedAt(), a.isActive());
    }
}
