package com.fintech.salesorg.application.port.in;

import com.fintech.salesorg.domain.AssigneeType;
import com.fintech.salesorg.domain.UnitAssignment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Asignación de empleados/distribuidores a unidades y consulta del alcance. */
public interface ManageAssignmentsUseCase {

    /** Asigna (o reasigna: cierra la vigente y crea la nueva). */
    UnitAssignment assign(AssignToUnitCommand command);

    /** Cierra la asignación vigente del asignado. Devuelve cuántas cerró (0 si no tenía). */
    int endAssignment(AssigneeType assigneeType, UUID assigneeId);

    /** La unidad actual del asignado, si tiene una vigente. */
    Optional<UnitAssignment> currentAssignment(AssigneeType assigneeType, UUID assigneeId);

    /** Asignaciones vigentes en una unidad (solo esa). */
    List<UnitAssignment> activeInUnit(UUID unitId);

    /** El alcance: asignaciones vigentes en todo el subárbol de la unidad. */
    List<UnitAssignment> scopeOfUnit(UUID unitId);

    /** Historial de asignaciones del asignado (vigente + cerradas). */
    List<UnitAssignment> history(AssigneeType assigneeType, UUID assigneeId);
}
