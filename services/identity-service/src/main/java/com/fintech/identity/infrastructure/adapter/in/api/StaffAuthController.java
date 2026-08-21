package com.fintech.identity.infrastructure.adapter.in.api;

import com.fintech.identity.application.StaffLoginCommand;
import com.fintech.identity.application.StaffProfile;
import com.fintech.identity.application.StaffSession;
import com.fintech.identity.application.port.in.FindStaffUseCase;
import com.fintech.identity.application.port.in.StaffLoginUseCase;
import com.fintech.identity.infrastructure.adapter.in.api.dto.RefreshRequest;
import com.fintech.identity.infrastructure.adapter.in.api.dto.StaffLoginRequest;
import com.fintech.identity.infrastructure.adapter.in.api.dto.StaffProfileResponse;
import com.fintech.identity.infrastructure.adapter.in.api.dto.StaffSessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Autenticación del personal de backoffice. Los tokens que emite llevan el claim
 * {@code channel=BACKOFFICE}, que el gateway exige en el subdominio {@code backoffice.*}.
 */
@RestController
@RequestMapping("/api/v1/auth/staff")
@Tag(name = "T1 — Identity & Auth (staff)", description = "Autenticación del personal de backoffice")
class StaffAuthController {

    private static final Logger log = LoggerFactory.getLogger(StaffAuthController.class);

    private final StaffLoginUseCase staffLoginUseCase;
    private final FindStaffUseCase findStaffUseCase;

    StaffAuthController(StaffLoginUseCase staffLoginUseCase, FindStaffUseCase findStaffUseCase) {
        this.staffLoginUseCase = staffLoginUseCase;
        this.findStaffUseCase = findStaffUseCase;
    }

    @Operation(summary = "Login de empleado — emite tokens con channel=BACKOFFICE")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Tokens emitidos"),
        @ApiResponse(responseCode = "401", description = "Credenciales incorrectas o cuenta inactiva"),
        @ApiResponse(responseCode = "423", description = "Cuenta bloqueada por intentos fallidos")
    })
    @PostMapping("/login")
    ResponseEntity<StaffSessionResponse> login(@Valid @RequestBody StaffLoginRequest request,
                                                HttpServletRequest httpRequest) {
        log.info("Staff login request email={}", request.email());
        StaffSession session = staffLoginUseCase.login(new StaffLoginCommand(
                request.email(),
                request.password(),
                ClientIpResolver.resolve(httpRequest),
                httpRequest.getHeader("User-Agent"),
                httpRequest.getHeader("X-Request-ID")));

        return ResponseEntity.ok(StaffSessionResponse.from(session));
    }

    @Operation(summary = "Renovar sesión de backoffice — relee los roles vigentes del empleado")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Nuevos tokens emitidos — el refresh anterior queda revocado"),
        @ApiResponse(responseCode = "401", description = "Refresh inválido, expirado, de otro canal, o cuenta inactiva")
    })
    @PostMapping("/refresh")
    ResponseEntity<StaffSessionResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        log.info("Staff token refresh request");
        return ResponseEntity.ok(StaffSessionResponse.from(
                staffLoginUseCase.refresh(request.refreshToken())));
    }

    @Operation(summary = "Logout — revoca todas las sesiones del empleado")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "204", description = "Sesiones revocadas")
    @PostMapping("/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal String staffUserIdStr) {
        staffLoginUseCase.logout(UUID.fromString(staffUserIdStr));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Empleado de la sesión actual — el BFF lo usa para pintar la sesión")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Perfil del empleado"),
        @ApiResponse(responseCode = "404", description = "El sujeto del token no es un empleado")
    })
    @GetMapping("/me")
    ResponseEntity<StaffProfileResponse> me(@AuthenticationPrincipal String staffUserIdStr) {
        StaffProfile profile = StaffProfile.from(
                findStaffUseCase.getById(UUID.fromString(staffUserIdStr)));
        return ResponseEntity.ok(StaffProfileResponse.from(profile));
    }
}
