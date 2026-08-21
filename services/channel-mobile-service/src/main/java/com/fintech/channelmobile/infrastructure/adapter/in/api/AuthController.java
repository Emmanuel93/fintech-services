package com.fintech.channelmobile.infrastructure.adapter.in.api;

import com.fintech.channelmobile.infrastructure.adapter.in.api.dto.*;
import com.fintech.channelmobile.infrastructure.adapter.out.client.IdentityClient;
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
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Auth", description = "Autenticación y sesión")
class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final IdentityClient identityClient;

    AuthController(IdentityClient identityClient) {
        this.identityClient = identityClient;
    }

    @Operation(summary = "Iniciar sesión con teléfono y contraseña")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Autenticación exitosa — retorna JWT"),
        @ApiResponse(responseCode = "401", description = "Credenciales incorrectas"),
        @ApiResponse(responseCode = "423", description = "Cuenta bloqueada")
    })
    @PostMapping("/auth/login")
    ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login request phone={} device={}", request.phone(), request.deviceId());
        IdentityClient.TokenResponse tokens =
                identityClient.login(request.phone(), request.password(), request.deviceId());
        log.info("Login success phone={}", request.phone());
        return ResponseEntity.ok(LoginResponse.of(
                tokens.accessToken(), tokens.refreshToken(), tokens.expiresIn()));
    }

    @Operation(summary = "Renovar access token usando refresh token")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Tokens renovados"),
        @ApiResponse(responseCode = "401", description = "Refresh token inválido o expirado")
    })
    @PostMapping("/auth/refresh")
    ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        log.info("Token refresh request");
        IdentityClient.TokenResponse tokens = identityClient.refresh(request.refreshToken());
        log.info("Token refresh success");
        return ResponseEntity.ok(LoginResponse.of(
                tokens.accessToken(), tokens.refreshToken(), tokens.expiresIn()));
    }

    @Operation(summary = "Cerrar sesión — revoca todos los tokens del usuario")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "204", description = "Sesión cerrada")
    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        log.info("Logout request");
        String bearer = extractBearer(request);
        identityClient.logout(bearer);
        log.info("Logout success");
        return ResponseEntity.noContent().build();
    }

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return "";
    }
}
