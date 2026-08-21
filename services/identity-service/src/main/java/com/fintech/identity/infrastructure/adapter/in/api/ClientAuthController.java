package com.fintech.identity.infrastructure.adapter.in.api;

import com.fintech.identity.application.*;
import com.fintech.identity.application.port.in.*;
import com.fintech.identity.infrastructure.adapter.in.api.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth/clients")
@Tag(name = "T1 — Client Auth", description = "Autenticación client/secret para sistemas externos con whitelist de IPs")
class ClientAuthController {

    private final ClientAuthUseCase clientAuthUseCase;
    private final RegisterClientUseCase registerClientUseCase;
    private final ManageClientWhitelistUseCase manageClientWhitelistUseCase;

    ClientAuthController(ClientAuthUseCase clientAuthUseCase,
                         RegisterClientUseCase registerClientUseCase,
                         ManageClientWhitelistUseCase manageClientWhitelistUseCase) {
        this.clientAuthUseCase = clientAuthUseCase;
        this.registerClientUseCase = registerClientUseCase;
        this.manageClientWhitelistUseCase = manageClientWhitelistUseCase;
    }

    @Operation(summary = "Autenticación client/secret — devuelve access + refresh token")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Tokens emitidos"),
        @ApiResponse(responseCode = "401", description = "Secret incorrecto o cliente no existe"),
        @ApiResponse(responseCode = "403", description = "IP fuera de la whitelist del cliente")
    })
    @PostMapping("/token")
    ResponseEntity<TokenResponse> token(@Valid @RequestBody ClientTokenRequest request,
                                        HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);
        TokenPair pair = clientAuthUseCase.authenticateClient(
                request.clientId(), request.clientSecret(), clientIp);
        return ResponseEntity.ok(TokenResponse.of(
                pair.accessToken(), pair.refreshToken(), pair.expiresIn()));
    }

    @Operation(summary = "Registrar nuevo cliente externo — solo ADMIN (el secret se devuelve una sola vez)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Cliente registrado — guardar clientSecret, no se vuelve a mostrar"),
        @ApiResponse(responseCode = "409", description = "clientId ya existe")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<ClientRegistrationResponse> register(
            @Valid @RequestBody RegisterClientRequest request) {
        ClientRegistrationResult result = registerClientUseCase.registerClient(
                request.clientId(), request.clientName(), request.roles(), request.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(toRegistrationResponse(result));
    }

    @Operation(summary = "Obtener info de un cliente — solo ADMIN")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Info del cliente"),
        @ApiResponse(responseCode = "404", description = "Cliente no encontrado")
    })
    @GetMapping("/{clientId}")
    ResponseEntity<ClientInfoResponse> getClient(@PathVariable String clientId) {
        ClientInfo info = manageClientWhitelistUseCase.getClient(clientId);
        return ResponseEntity.ok(toInfoResponse(info));
    }

    @Operation(summary = "Deshabilitar cliente — solo ADMIN")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "204", description = "Cliente deshabilitado")
    @DeleteMapping("/{clientId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disableClient(@PathVariable String clientId) {
        manageClientWhitelistUseCase.disableClient(clientId);
    }

    @Operation(summary = "Agregar entrada a whitelist de IPs del cliente — solo ADMIN")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "201", description = "Entrada agregada")
    @PostMapping("/{clientId}/whitelist")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<WhitelistEntryResponse> addEntry(@PathVariable String clientId,
                                                    @Valid @RequestBody WhitelistEntryRequest request) {
        WhitelistEntryResult result = manageClientWhitelistUseCase.addWhitelistEntry(
                clientId, request.cidr(), request.label());
        return ResponseEntity.status(HttpStatus.CREATED).body(toEntryResponse(result));
    }

    @Operation(summary = "Listar whitelist de IPs del cliente — solo ADMIN")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Lista de entradas CIDR")
    @GetMapping("/{clientId}/whitelist")
    ResponseEntity<List<WhitelistEntryResponse>> listEntries(@PathVariable String clientId) {
        List<WhitelistEntryResponse> entries = manageClientWhitelistUseCase
                .listWhitelistEntries(clientId).stream()
                .map(this::toEntryResponse)
                .toList();
        return ResponseEntity.ok(entries);
    }

    @Operation(summary = "Eliminar entrada de whitelist — solo ADMIN")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "204", description = "Entrada eliminada")
    @DeleteMapping("/{clientId}/whitelist/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeEntry(@PathVariable String clientId, @PathVariable UUID entryId) {
        manageClientWhitelistUseCase.removeWhitelistEntry(entryId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private ClientRegistrationResponse toRegistrationResponse(ClientRegistrationResult r) {
        return new ClientRegistrationResponse(r.id(), r.clientId(), r.clientSecret(),
                r.clientName(), r.status(), r.roles(), r.expiresAt(), r.createdAt());
    }

    private ClientInfoResponse toInfoResponse(ClientInfo info) {
        return new ClientInfoResponse(info.id(), info.clientId(), info.clientName(),
                info.status(), info.roles(), info.expiresAt(), info.createdAt());
    }

    private WhitelistEntryResponse toEntryResponse(WhitelistEntryResult r) {
        return new WhitelistEntryResponse(r.id(), r.cidr(), r.label(), r.createdAt());
    }
}
