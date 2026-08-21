package com.fintech.salesorg.infrastructure.adapter.in.api;

import com.fintech.salesorg.application.port.in.AssignToUnitCommand;
import com.fintech.salesorg.application.port.in.ManageAssignmentsUseCase;
import com.fintech.salesorg.domain.AssigneeType;
import com.fintech.salesorg.infrastructure.adapter.in.api.dto.AssignRequest;
import com.fintech.salesorg.infrastructure.adapter.in.api.dto.UnitAssignmentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Asignaciones de empleados/distribuidores a unidades y consulta del alcance.
 *
 * <p>La asignación es append-only: reasignar cierra la vigente y crea otra (lo resuelve el servicio).
 * El alcance de una unidad —toda la gente de su subárbol— sale de una sola consulta LTREE y es lo que
 * el dashboard con alcance consumirá.
 */
@RestController
@RequestMapping("/api/v1/sales-org")
@Tag(name = "Sales Org — Assignments", description = "Pertenencia a unidades y alcance")
class UnitAssignmentController {

    private static final Logger log = LoggerFactory.getLogger(UnitAssignmentController.class);

    private final ManageAssignmentsUseCase assignmentsUseCase;

    UnitAssignmentController(ManageAssignmentsUseCase assignmentsUseCase) {
        this.assignmentsUseCase = assignmentsUseCase;
    }

    @PostMapping("/units/{unitId}/assignments")
    @Operation(summary = "Asignar (o reasignar) a una unidad")
    ResponseEntity<UnitAssignmentResponse> assign(
            @PathVariable UUID unitId,
            @Valid @RequestBody AssignRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String staffUserId) {
        log.info("POST /units/{}/assignments {} {}", unitId, request.assigneeType(), request.assigneeId());
        var saved = assignmentsUseCase.assign(new AssignToUnitCommand(
                unitId, request.assigneeType(), request.assigneeId(),
                request.assignmentRole(), staffUserId));
        return ResponseEntity.status(201).body(UnitAssignmentResponse.from(saved));
    }

    @GetMapping("/units/{unitId}/assignments")
    @Operation(summary = "Asignaciones vigentes de una unidad (solo esa)")
    List<UnitAssignmentResponse> activeInUnit(@PathVariable UUID unitId) {
        return assignmentsUseCase.activeInUnit(unitId).stream().map(UnitAssignmentResponse::from).toList();
    }

    @GetMapping("/units/{unitId}/scope")
    @Operation(summary = "Alcance de la unidad: asignaciones vigentes en todo su subárbol")
    List<UnitAssignmentResponse> scope(@PathVariable UUID unitId) {
        return assignmentsUseCase.scopeOfUnit(unitId).stream().map(UnitAssignmentResponse::from).toList();
    }

    @GetMapping("/assignments/{assigneeType}/{assigneeId}/current")
    @Operation(summary = "Unidad actual del asignado")
    ResponseEntity<UnitAssignmentResponse> current(@PathVariable AssigneeType assigneeType,
                                                   @PathVariable UUID assigneeId) {
        return assignmentsUseCase.currentAssignment(assigneeType, assigneeId)
                .map(UnitAssignmentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/assignments/{assigneeType}/{assigneeId}/history")
    @Operation(summary = "Historial de asignaciones del asignado")
    List<UnitAssignmentResponse> history(@PathVariable AssigneeType assigneeType,
                                         @PathVariable UUID assigneeId) {
        return assignmentsUseCase.history(assigneeType, assigneeId).stream()
                .map(UnitAssignmentResponse::from).toList();
    }

    @DeleteMapping("/assignments/{assigneeType}/{assigneeId}")
    @Operation(summary = "Cerrar la asignación vigente del asignado (deja su unidad)")
    ResponseEntity<Void> end(@PathVariable AssigneeType assigneeType, @PathVariable UUID assigneeId) {
        int ended = assignmentsUseCase.endAssignment(assigneeType, assigneeId);
        return ended > 0 ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
