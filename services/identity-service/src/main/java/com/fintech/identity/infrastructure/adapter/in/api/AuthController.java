package com.fintech.identity.infrastructure.adapter.in.api;

import com.fintech.identity.application.LoginCommand;
import com.fintech.identity.application.LoginResult;
import com.fintech.identity.application.TokenPair;
import com.fintech.identity.application.TokenValidationResult;
import com.fintech.identity.application.port.in.*;
import com.fintech.identity.domain.TokenException;
import com.fintech.identity.infrastructure.adapter.in.api.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import com.fintech.identity.application.port.out.CredentialRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "T1 — Identity & Auth", description = "Autenticación y gestión de tokens JWT")
class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final LoginUseCase loginUseCase;
    private final RefreshTokenUseCase refreshTokenUseCase;
    private final LogoutUseCase logoutUseCase;
    private final ValidateTokenUseCase validateTokenUseCase;
    private final CreateCredentialUseCase createCredentialUseCase;
    private final CredentialRepository credentialRepository;

    AuthController(LoginUseCase loginUseCase,
                   RefreshTokenUseCase refreshTokenUseCase,
                   LogoutUseCase logoutUseCase,
                   ValidateTokenUseCase validateTokenUseCase,
                   CreateCredentialUseCase createCredentialUseCase,
                   CredentialRepository credentialRepository) {
        this.loginUseCase = loginUseCase;
        this.refreshTokenUseCase = refreshTokenUseCase;
        this.logoutUseCase = logoutUseCase;
        this.validateTokenUseCase = validateTokenUseCase;
        this.createCredentialUseCase = createCredentialUseCase;
        this.credentialRepository = credentialRepository;
    }

    @Operation(summary = "Login con NIP — 200 con tokens o 202 si el party tiene 2FA activo")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Tokens emitidos (sin 2FA)"),
        @ApiResponse(responseCode = "202", description = "2FA requerido — usar mfaToken en POST /mfa/verify"),
        @ApiResponse(responseCode = "401", description = "Credenciales incorrectas"),
        @ApiResponse(responseCode = "423", description = "Cuenta bloqueada por intentos fallidos")
    })
    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                             HttpServletRequest httpRequest) {
        log.info("Login request username={} device={}", request.username(), request.deviceId());
        LoginCommand command = new LoginCommand(
                request.username(),
                request.password(),
                ClientIpResolver.resolve(httpRequest),
                httpRequest.getHeader("User-Agent"),
                request.deviceId(),
                httpRequest.getHeader("X-Request-ID"));

        return switch (loginUseCase.login(command)) {
            case LoginResult.TokensIssued(var tokens) -> {
                log.info("Login success username={}", request.username());
                yield ResponseEntity.ok(TokenResponse.of(
                        tokens.accessToken(), tokens.refreshToken(), tokens.expiresIn()));
            }
            case LoginResult.MfaRequired(var mfaToken) -> {
                log.info("Login MFA required username={}", request.username());
                yield ResponseEntity.accepted().body(MfaPendingResponse.of(mfaToken));
            }
        };
    }

    @Operation(summary = "Renovar tokens — rotación de refresh token")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Nuevos tokens emitidos — el refresh anterior queda revocado"),
        @ApiResponse(responseCode = "401", description = "Refresh token inválido, expirado o revocado")
    })
    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        log.info("Token refresh request");
        TokenPair pair = refreshTokenUseCase.refresh(request.refreshToken());
        log.info("Token refresh success");
        return ResponseEntity.ok(TokenResponse.of(
                pair.accessToken(), pair.refreshToken(), pair.expiresIn()));
    }

    @Operation(summary = "Logout — revoca todas las sesiones del party")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "204", description = "Sesiones revocadas")
    @PostMapping("/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal String partyIdStr) {
        log.info("Logout request partyId={}", partyIdStr);
        logoutUseCase.logout(UUID.fromString(partyIdStr));
        log.info("Logout success partyId={}", partyIdStr);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Validar token JWT — consumido síncronamente por otros módulos")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Token válido — retorna partyId, roles, deviceId"),
        @ApiResponse(responseCode = "401", description = "Token inválido, expirado o revocado")
    })
    @GetMapping("/validate")
    ResponseEntity<TokenValidationResponse> validate(HttpServletRequest request) {
        String bearer = extractBearer(request);
        TokenValidationResult result = validateTokenUseCase.validate(bearer);
        return ResponseEntity.ok(new TokenValidationResponse(
                result.partyId(), result.roles(), result.deviceId(), result.channel()));
    }

    @Operation(summary = "Crear credencial NIP — solo ADMIN (bootstrap / D0 delegado)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Credencial creada"),
        @ApiResponse(responseCode = "409", description = "Ya existe una credencial del mismo tipo para este party")
    })
    @PostMapping("/credentials")
    @ResponseStatus(HttpStatus.CREATED)
    void createCredential(@Valid @RequestBody CredentialCreateRequest request) {
        createCredentialUseCase.createCredential(
                request.partyId(), request.username(), request.credentialType(), request.password());
    }

    @Operation(summary = "Resolver a quién pertenece un usuario de acceso (teléfono)",
            description = "Devuelve el party dueño de esa credencial. Existe para la auditoría del "
                        + "backoffice: la bitácora guarda el usuario con el que se entró —un "
                        + "teléfono—, no el party, y sin esto no hay forma de contestar 'de quién "
                        + "es este número'. No expone hash ni intentos fallidos.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Credencial encontrada"),
        @ApiResponse(responseCode = "404", description = "Ningún acceso con ese usuario")
    })
    @GetMapping("/credentials/lookup")
    ResponseEntity<CredentialOwnerResponse> lookupCredential(@RequestParam String username) {
        return credentialRepository.findByUsername(username.trim())
                .map(c -> ResponseEntity.ok(new CredentialOwnerResponse(
                        c.getPartyId(), c.getUsername(), c.getCredentialType().name(),
                        c.getStatus().name(), c.getLastLoginAt())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Dueño de una credencial. Sin secretos: sirve para atribuir, no para autenticar. */
    public record CredentialOwnerResponse(
            UUID partyId, String username, String credentialType, String status,
            java.time.Instant lastLoginAt) {}

    // ── Helpers ───────────────────────────────────────────────────────────

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        throw new TokenException("Missing or malformed Authorization header");
    }
}
