package com.fintech.identity.infrastructure.adapter.in.api;

import com.fintech.identity.application.CreateStaffCommand;
import com.fintech.identity.application.port.in.FindStaffUseCase;
import com.fintech.identity.application.port.in.ManageStaffUseCase;
import com.fintech.identity.domain.StaffRole;
import com.fintech.identity.domain.StaffStatus;
import com.fintech.identity.infrastructure.adapter.in.api.dto.ChangeStaffPasswordRequest;
import com.fintech.identity.infrastructure.adapter.in.api.dto.ChangeStaffRolesRequest;
import com.fintech.identity.infrastructure.adapter.in.api.dto.CreateStaffRequest;
import com.fintech.identity.infrastructure.adapter.in.api.dto.StaffUserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Directorio de personal. Todo el controlador exige rol ADMIN (ver {@code SecurityConfig}).
 * La baja es lógica: el registro se conserva porque la bitácora, las comisiones y las asignaciones
 * de cartera lo referencian.
 */
@RestController
@RequestMapping("/api/v1/staff")
@Tag(name = "T1 — Identity & Auth (staff)", description = "Administración del personal de backoffice")
@SecurityRequirement(name = "bearerAuth")
class StaffController {

    private final ManageStaffUseCase manageStaffUseCase;
    private final FindStaffUseCase findStaffUseCase;

    StaffController(ManageStaffUseCase manageStaffUseCase, FindStaffUseCase findStaffUseCase) {
        this.manageStaffUseCase = manageStaffUseCase;
        this.findStaffUseCase = findStaffUseCase;
    }

    @Operation(summary = "Directorio de personal, con filtros opcionales por estatus y rol")
    @GetMapping
    List<StaffUserResponse> find(@RequestParam(required = false) StaffStatus status,
                                  @RequestParam(required = false) StaffRole role) {
        return findStaffUseCase.find(status, role).stream()
                .map(StaffUserResponse::from)
                .toList();
    }

    @Operation(summary = "Consultar un empleado")
    @ApiResponse(responseCode = "404", description = "El empleado no existe")
    @GetMapping("/{staffUserId}")
    StaffUserResponse getById(@PathVariable UUID staffUserId) {
        return StaffUserResponse.from(findStaffUseCase.getById(staffUserId));
    }

    @Operation(summary = "Alta de empleado")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Empleado creado"),
        @ApiResponse(responseCode = "409", description = "Ya existe un empleado con ese correo")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    StaffUserResponse create(@Valid @RequestBody CreateStaffRequest request) {
        return StaffUserResponse.from(manageStaffUseCase.create(new CreateStaffCommand(
                request.email(),
                request.fullName(),
                request.curp(),
                request.employeeType(),
                request.distributorPartyId(),
                request.roles(),
                request.password())));
    }

    @Operation(summary = "Cambiar los roles de un empleado — aplica en su siguiente refresh")
    @PutMapping("/{staffUserId}/roles")
    StaffUserResponse changeRoles(@PathVariable UUID staffUserId,
                                   @Valid @RequestBody ChangeStaffRolesRequest request) {
        return StaffUserResponse.from(manageStaffUseCase.changeRoles(staffUserId, request.roles()));
    }

    @Operation(summary = "Cambiar la contraseña — cierra las sesiones vivas del empleado")
    @PutMapping("/{staffUserId}/password")
    StaffUserResponse changePassword(@PathVariable UUID staffUserId,
                                      @Valid @RequestBody ChangeStaffPasswordRequest request) {
        return StaffUserResponse.from(
                manageStaffUseCase.changePassword(staffUserId, request.password()));
    }

    @Operation(summary = "Suspender — revoca sus sesiones en el acto")
    @PutMapping("/{staffUserId}/suspend")
    StaffUserResponse suspend(@PathVariable UUID staffUserId) {
        return StaffUserResponse.from(manageStaffUseCase.suspend(staffUserId));
    }

    @Operation(summary = "Reactivar a un empleado suspendido")
    @ApiResponse(responseCode = "409", description = "El empleado está dado de baja")
    @PutMapping("/{staffUserId}/reactivate")
    StaffUserResponse reactivate(@PathVariable UUID staffUserId) {
        return StaffUserResponse.from(manageStaffUseCase.reactivate(staffUserId));
    }

    @Operation(summary = "Baja lógica — revoca sus sesiones y conserva el registro histórico")
    @DeleteMapping("/{staffUserId}")
    StaffUserResponse disable(@PathVariable UUID staffUserId) {
        return StaffUserResponse.from(manageStaffUseCase.disable(staffUserId));
    }
}
