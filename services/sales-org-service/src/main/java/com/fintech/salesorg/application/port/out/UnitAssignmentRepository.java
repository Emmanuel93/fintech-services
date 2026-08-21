package com.fintech.salesorg.application.port.out;

import com.fintech.salesorg.domain.AssigneeType;
import com.fintech.salesorg.domain.UnitAssignment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnitAssignmentRepository {

    UnitAssignment save(UnitAssignment assignment);

    /**
     * Cierra la asignación vigente del asignado (si la hay) con una actualización que se ejecuta de
     * inmediato. Se llama ANTES de insertar la nueva, para no violar el índice único parcial de
     * "una asignación activa por asignado" bajo el orden de flush de Hibernate. Devuelve cuántas cerró.
     */
    int endActive(AssigneeType assigneeType, UUID assigneeId, Instant endedAt);

    Optional<UnitAssignment> findActiveByAssignee(AssigneeType assigneeType, UUID assigneeId);

    /** Asignaciones vigentes de una unidad (solo esa unidad, no el subárbol). */
    List<UnitAssignment> findActiveByUnit(UUID unitId);

    /**
     * Asignaciones vigentes en TODO el subárbol de la unidad de {@code ancestorPath} (une con
     * org_units por el path LTREE). Es el alcance: "toda la gente bajo esta región", en una consulta.
     */
    List<UnitAssignment> findActiveInSubtree(String ancestorPath);

    /** Historial completo del asignado (vigente + cerradas), más reciente primero. */
    List<UnitAssignment> findHistoryByAssignee(AssigneeType assigneeType, UUID assigneeId);
}
