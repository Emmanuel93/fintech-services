package com.fintech.identity.infrastructure.adapter.in.api;

import com.fintech.identity.application.port.in.FindStaffUseCase;
import com.fintech.identity.domain.StaffRole;
import com.fintech.identity.domain.StaffStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Directorio de nombres del personal, para poblar selectores y rosters del backoffice.
 *
 * <p>A diferencia de {@code /api/v1/staff} (ADMIN), esto lo consulta cualquier
 * empleado autenticado (un ejecutivo o supervisor que asigna cartera). Por eso
 * devuelve solo <b>id y nombre</b> — nada de correos ni roles, que no hacen falta
 * para pintar un nombre y no deben filtrarse a no-admins.
 *
 * <p>{@code /api/v1/executives} responde el selector de ejecutivos;
 * {@code /api/v1/executives/staff} responde <i>todo</i> el personal activo. Hace falta el
 * segundo porque la estructura comercial asigna personas que no siempre son EXECUTIVE —una
 * sucursal puede colgar de un supervisor— y filtrarlas por rol dejaba a media plantilla
 * mostrándose como un UUID en pantalla.
 */
@RestController
@RequestMapping("/api/v1/executives")
@Tag(name = "T1 — Identity & Auth (staff)", description = "Directorio de ejecutivos (selector)")
@SecurityRequirement(name = "bearerAuth")
class ExecutiveDirectoryController {

    private final FindStaffUseCase findStaffUseCase;

    ExecutiveDirectoryController(FindStaffUseCase findStaffUseCase) {
        this.findStaffUseCase = findStaffUseCase;
    }

    @Operation(summary = "Ejecutivos de cuenta activos (id + nombre)")
    @GetMapping
    List<ExecutiveResponse> list() {
        return findStaffUseCase.find(StaffStatus.ACTIVE, StaffRole.EXECUTIVE).stream()
                .map(s -> new ExecutiveResponse(s.getStaffUserId(), s.getFullName()))
                .toList();
    }

    @Operation(summary = "Todo el personal activo (id + nombre)",
            description = "Para resolver a quién corresponde un identificador —el roster de una "
                        + "unidad comercial, el autor de un movimiento— sin exigir rol de "
                        + "administración ni exponer más que el nombre.")
    @GetMapping("/staff")
    List<ExecutiveResponse> allStaff() {
        return findStaffUseCase.find(StaffStatus.ACTIVE, null).stream()
                .map(s -> new ExecutiveResponse(s.getStaffUserId(), s.getFullName()))
                .toList();
    }

    record ExecutiveResponse(UUID id, String name) {}
}
