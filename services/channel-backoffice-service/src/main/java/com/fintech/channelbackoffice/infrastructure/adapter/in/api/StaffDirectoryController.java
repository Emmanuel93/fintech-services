package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Administración del personal desde la consola. Es passthrough puro a identity-service, que vuelve
 * a exigir ADMIN con el token del solicitante — la autorización se decide una sola vez y en el dueño
 * del dato, no en dos lugares que puedan desincronizarse.
 */
@RestController
@RequestMapping("/staff")
@Tag(name = "Personal", description = "Directorio de empleados del backoffice")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "403", description = "El rol del solicitante no es ADMIN")
class StaffDirectoryController {

    private final IdentityClient identityClient;

    StaffDirectoryController(IdentityClient identityClient) {
        this.identityClient = identityClient;
    }

    @Operation(summary = "Directorio de personal, con filtros opcionales")
    @GetMapping
    List<IdentityClient.StaffUserResponse> list(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String role,
                                                 HttpServletRequest http) {
        return identityClient.listStaff(StaffAuthController.bearerToken(http), status, role);
    }

    @Operation(summary = "Consultar un empleado")
    @GetMapping("/{staffUserId}")
    IdentityClient.StaffUserResponse getById(@PathVariable UUID staffUserId, HttpServletRequest http) {
        return identityClient.getStaff(StaffAuthController.bearerToken(http), staffUserId);
    }

    @Operation(summary = "Alta de empleado")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    IdentityClient.StaffUserResponse create(@Valid @RequestBody CreateStaffRequest request,
                                             HttpServletRequest http) {
        return identityClient.createStaff(StaffAuthController.bearerToken(http), request);
    }

    @Operation(summary = "Cambiar los roles de un empleado")
    @PutMapping("/{staffUserId}/roles")
    IdentityClient.StaffUserResponse changeRoles(@PathVariable UUID staffUserId,
                                                  @Valid @RequestBody ChangeRolesRequest request,
                                                  HttpServletRequest http) {
        return identityClient.changeRoles(
                StaffAuthController.bearerToken(http), staffUserId, request.roles());
    }

    @Operation(summary = "Cambiar la contraseña — cierra las sesiones vivas del empleado")
    @PutMapping("/{staffUserId}/password")
    IdentityClient.StaffUserResponse changePassword(@PathVariable UUID staffUserId,
                                                     @Valid @RequestBody ChangePasswordRequest request,
                                                     HttpServletRequest http) {
        return identityClient.changePassword(
                StaffAuthController.bearerToken(http), staffUserId, request.password());
    }

    @Operation(summary = "Suspender — revoca sus sesiones en el acto")
    @PutMapping("/{staffUserId}/suspend")
    IdentityClient.StaffUserResponse suspend(@PathVariable UUID staffUserId, HttpServletRequest http) {
        return identityClient.suspend(StaffAuthController.bearerToken(http), staffUserId);
    }

    @Operation(summary = "Reactivar a un empleado suspendido")
    @PutMapping("/{staffUserId}/reactivate")
    IdentityClient.StaffUserResponse reactivate(@PathVariable UUID staffUserId, HttpServletRequest http) {
        return identityClient.reactivate(StaffAuthController.bearerToken(http), staffUserId);
    }

    @Operation(summary = "Baja lógica — conserva el registro histórico")
    @DeleteMapping("/{staffUserId}")
    IdentityClient.StaffUserResponse disable(@PathVariable UUID staffUserId, HttpServletRequest http) {
        return identityClient.disable(StaffAuthController.bearerToken(http), staffUserId);
    }

    // ── Contratos de entrada ──────────────────────────────────────────────

    record CreateStaffRequest(
            @NotBlank String email,
            @NotBlank String fullName,

            /**
             * CURP del empleado. Opcional en el contrato —el personal ya dado de alta no la tiene—
             * pero la exige la bitácora para identificar plenamente a quien actúa. El formato lo
             * valida identity, que es el dueño del dato; repetir aquí la expresión regular sólo
             * garantizaría que las dos se separen.
             */
            String curp,

            @NotBlank String employeeType,
            UUID distributorPartyId,
            @NotEmpty Set<String> roles,
            @NotBlank @Size(min = 12) String password
    ) {}

    record ChangeRolesRequest(@NotEmpty Set<String> roles) {}

    record ChangePasswordRequest(@NotBlank @Size(min = 12) String password) {}
}
