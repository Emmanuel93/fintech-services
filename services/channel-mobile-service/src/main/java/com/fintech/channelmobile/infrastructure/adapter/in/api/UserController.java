package com.fintech.channelmobile.infrastructure.adapter.in.api;

import com.fintech.channelmobile.infrastructure.adapter.in.api.dto.ProfileResponse;
import com.fintech.channelmobile.infrastructure.adapter.out.client.OriginationClient;
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
    private final OriginationClient originationClient;

    UserController(PartyClient partyClient, OriginationClient originationClient) {
        this.partyClient = partyClient;
        this.originationClient = originationClient;
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
        // El domicilio, el género y el contacto verificado no están en party: los capturó el
        // alta y viven en origination. Si esa consulta falla, el perfil sale igual con lo que
        // party sí tiene —una pantalla incompleta es mejor que un 500 en la propia.
        OriginationClient.ProspectDetail d = null;
        try {
            d = originationClient.getProspect(prospectId);
        } catch (RuntimeException e) {
            log.warn("perfil sin expediente de origination para {}: {}", prospectId, e.getMessage());
        }
        return ResponseEntity.ok(toProfile(p, d));
    }

    /** Junta el Party (identidad verificada) con el expediente del alta. */
    private ProfileResponse toProfile(PartyClient.PartyResponse p,
                                      OriginationClient.ProspectDetail d) {
        String fullName = Stream.of(p.firstName(), p.lastName1(), p.lastName2())
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" "));
        OriginationClient.ProspectDetail.Address dir = d == null ? null : d.address();
        return new ProfileResponse(
                p.partyId() != null ? p.partyId().toString() : "",
                fullName,
                d == null ? "" : nz(d.phone()),
                d == null ? "" : nz(d.email()),
                p.curp() != null ? p.curp() : "",
                p.rfc() != null ? p.rfc() : "",
                mapKycStatus(p.status()),
                p.createdAt() != null ? p.createdAt().toString() : "",
                p.dateOfBirth() != null ? p.dateOfBirth().toString() : "",
                dir == null ? "" : nz(dir.state()),
                d == null ? "" : mapGender(d.gender()),
                d == null ? "" : nz(d.stateOfBirth()),
                dir == null ? "" : nz(dir.street()),
                dir == null ? "" : nz(dir.exteriorNumber()),
                dir == null ? "" : nz(dir.interiorNumber()),
                dir == null ? "" : nz(dir.neighborhood()),
                dir == null ? "" : nz(dir.municipality()),
                dir == null ? "" : nz(dir.city()),
                dir == null ? "" : nz(dir.postalCode())
        );
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** El alta guarda MALE/FEMALE; la pantalla lo enseña como lo dice la INE. */
    private String mapGender(String gender) {
        if (gender == null) return "";
        return switch (gender.toUpperCase()) {
            case "MALE", "M", "H" -> "Masculino";
            case "FEMALE", "F", "M_F" -> "Femenino";
            default -> gender;
        };
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
