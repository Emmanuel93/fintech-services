package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.in.api.dto.RefreshRequest;
import com.fintech.channelbackoffice.infrastructure.adapter.in.api.dto.SessionResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.in.api.dto.StaffLoginRequest;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Sesión de la consola. Delega en identity-service, que es el único emisor de tokens. */
@RestController
@RequestMapping("/auth/staff")
@Tag(name = "Sesión", description = "Autenticación del personal de backoffice")
class StaffAuthController {

    private static final Logger log = LoggerFactory.getLogger(StaffAuthController.class);

    private final IdentityClient identityClient;

    StaffAuthController(IdentityClient identityClient) {
        this.identityClient = identityClient;
    }

    @Operation(summary = "Iniciar sesión en el backoffice")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sesión iniciada"),
        @ApiResponse(responseCode = "401", description = "Credenciales incorrectas o cuenta inactiva"),
        @ApiResponse(responseCode = "423", description = "Cuenta bloqueada por intentos fallidos")
    })
    @PostMapping("/login")
    SessionResponse login(@Valid @RequestBody StaffLoginRequest request, HttpServletRequest httpRequest) {
        log.info("Login de backoffice email={}", request.email());
        return SessionResponse.from(identityClient.staffLogin(
                request.email(),
                request.password(),
                httpRequest.getHeader("X-Forwarded-For"),
                httpRequest.getHeader(HttpHeaders.USER_AGENT)));
    }

    @Operation(summary = "Renovar la sesión — relee los roles vigentes del empleado")
    @PostMapping("/refresh")
    SessionResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return SessionResponse.from(identityClient.staffRefresh(request.refreshToken()));
    }

    @Operation(summary = "Cerrar sesión en todos los dispositivos")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        identityClient.staffLogout(bearerToken(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Empleado de la sesión actual")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me")
    SessionResponse.StaffUser me(HttpServletRequest httpRequest) {
        return SessionResponse.user(identityClient.staffMe(bearerToken(httpRequest)));
    }

    /**
     * El token del solicitante se reenvía a identity para que la autorización se resuelva en el
     * dueño del dato y no se duplique aquí.
     */
    static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Falta el encabezado Authorization");
        }
        return header.substring(7);
    }
}
