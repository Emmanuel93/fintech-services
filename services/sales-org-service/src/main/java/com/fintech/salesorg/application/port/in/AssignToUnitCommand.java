package com.fintech.salesorg.application.port.in;

import com.fintech.salesorg.domain.AssigneeType;

import java.util.UUID;

/**
 * Asigna un empleado o distribuidor a una unidad. Si el asignado ya tenía una unidad, esto lo
 * reasigna: la anterior se cierra y esta queda vigente (append-only).
 */
public record AssignToUnitCommand(
        UUID unitId,
        AssigneeType assigneeType,
        UUID assigneeId,
        String assignmentRole,
        String assignedBy) {}
