package com.fintech.channelmobile.infrastructure.adapter.in.api;

import com.fintech.channelmobile.infrastructure.adapter.in.api.dto.ProfileResponse;
import com.fintech.channelmobile.infrastructure.adapter.out.client.PartyClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@Tag(name = "User", description = "Perfil del usuario autenticado")
class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final PartyClient partyClient;

    UserController(PartyClient partyClient) {
        this.partyClient = partyClient;
    }

    @Operation(summary = "Obtener perfil del usuario autenticado")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/users/me")
    ResponseEntity<ProfileResponse> getMe(HttpServletRequest request) {
        String userIdHeader = request.getHeader("X-User-Id");
        // El sub del JWT (X-User-Id) es el prospectId; party-service guarda el Party con
        // su propio partyId ligado al prospectId → se resuelve por prospectId.
        UUID prospectId = UUID.fromString(userIdHeader);
        log.info("GET /users/me prospectId={}", prospectId);
        PartyClient.PartyResponse p = partyClient.getByProspectId(prospectId);
        return ResponseEntity.ok(toProfile(p));
    }

    /** Adapta el Party al shape que consume la app (fa_profile). */
    private ProfileResponse toProfile(PartyClient.PartyResponse p) {
        String fullName = Stream.of(p.firstName(), p.lastName1(), p.lastName2())
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" "));
        return new ProfileResponse(
                p.partyId() != null ? p.partyId().toString() : "",
                fullName,
                "",   // phone — party no lo expone hoy
                "",   // email — party no lo expone hoy
                p.curp() != null ? p.curp() : "",
                p.rfc() != null ? p.rfc() : "",
                mapKycStatus(p.status()),
                p.createdAt() != null ? p.createdAt().toString() : "",
                p.dateOfBirth() != null ? p.dateOfBirth().toString() : "",
                ""    // state — party no lo expone hoy
        );
    }

    private String mapKycStatus(String status) {
        if (status == null) return "";
        return switch (status.toUpperCase()) {
            case "ACTIVE", "VERIFIED" -> "Verificado";
            case "PROSPECT" -> "En validación";
            case "BLACKLISTED", "REJECTED" -> "Rechazado";
            default -> status;
        };
    }
}
