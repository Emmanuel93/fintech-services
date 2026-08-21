package com.fintech.salesorg.infrastructure.adapter.in.api.dto;

import com.fintech.salesorg.domain.AssigneeType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Asigna un empleado/distribuidor a la unidad del path. {@code assignedBy} no viaja en el cuerpo:
 * se toma del empleado autenticado. {@code assignmentRole} opcional (por defecto MEMBER).
 */
public record AssignRequest(
        @NotNull AssigneeType assigneeType,
        @NotNull UUID assigneeId,
        String assignmentRole) {}
