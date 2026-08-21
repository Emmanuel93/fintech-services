package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.BeneficiaryClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Mesa de KYC de beneficiarios: la bandeja de colocaciones y su ficha.
 *
 * <p><b>Validamos identidad, no riesgo.</b> El score del beneficiario es informativo y nunca
 * bloquea la colocación —la decisión de colocar es de la distribuidora, y queda firmada por ella—;
 * lo único que detiene el depósito es una identidad no comprobada. Por eso la bandeja filtra por
 * {@code identityStatus} y no por score, y por eso esta consola no expone ninguna acción para
 * aprobar o rechazar una colocación: eso no es de la mesa.
 *
 * <p><b>Composición (patrón B).</b> La consulta la resuelve entera {@code beneficiary-service},
 * paginada e indexada. Aquí sólo se enriquece con los nombres de las distribuidoras, y en
 * <b>una</b> llamada a {@code party /batch} por página — nunca una por fila. Son exactamente dos
 * llamadas de dominio por carga de bandeja, sin importar cuántas colocaciones traiga.
 */
@RestController
@Tag(name = "Beneficiarios", description = "Mesa de KYC de la colocación B2B2C")
class BeneficiariesController {

    private static final Logger log = LoggerFactory.getLogger(BeneficiariesController.class);

    private final BeneficiaryClient beneficiaryClient;
    private final PartyClient partyClient;

    BeneficiariesController(BeneficiaryClient beneficiaryClient, PartyClient partyClient) {
        this.beneficiaryClient = beneficiaryClient;
        this.partyClient       = partyClient;
    }

    @Operation(summary = "Bandeja de beneficiarios por verificar",
               description = "Filtros: distribuidora, estado de la colocación, estado de identidad "
                           + "y SLA (`stalledDays` = sin moverse desde hace N días). "
                           + "`identityDecision` es la pregunta de la mesa: PENDING = por dictaminar. "
                           + "El score no filtra: no bloquea la colocación.")
    @ApiResponse(responseCode = "200", description = "Página de colocaciones con la distribuidora resuelta")
    @GetMapping("/beneficiaries/placements")
    ResponseEntity<Map<String, Object>> placements(
            @RequestParam(required = false) List<UUID> distributorPartyId,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) List<String> identityDecision,
            @RequestParam(required = false) Integer stalledDays,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Map<String, Object> result = beneficiaryClient.searchPlacements(
                distributorPartyId, status, identityDecision, stalledDays, page, size);

        List<Map<String, Object>> rows = content(result);
        Map<UUID, String> distributorNames = resolveDistributorNames(rows);

        List<Map<String, Object>> enriched = rows.stream().map(row -> {
            Map<String, Object> out = new LinkedHashMap<>(row);
            out.put("distributorName", distributorNames.get(uuid(row.get("distributorPartyId"))));
            return out;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>(result);
        body.put("content", enriched);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Ficha de una colocación",
               description = "Estado, bitácora de transiciones y la evidencia de identidad "
                           + "disponible. Mientras la captura granular no exista, "
                           + "`identityEvidence.available` viene en false con su motivo.")
    @GetMapping("/beneficiaries/placements/{placementId}")
    ResponseEntity<Map<String, Object>> placementDetail(@PathVariable UUID placementId) {
        Map<String, Object> detail = beneficiaryClient.placementDetail(placementId);

        Map<String, Object> body = new LinkedHashMap<>(detail);
        if (detail.get("placement") instanceof Map<?, ?> placement) {
            UUID distributorPartyId = uuid(placement.get("distributorPartyId"));
            Map<String, Object> withName = new LinkedHashMap<>((Map<String, Object>) placement);
            withName.put("distributorName", nameOf(distributorPartyId));
            body.put("placement", withName);
        }
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Dictaminar la identidad de una beneficiaria",
               description = "El juicio de la mesa de KYC: **lo único que habilita el depósito**. "
                           + "Mientras no haya proveedor contratado, toda revisión es manual. "
                           + "No decide sobre el riesgo — eso es de la distribuidora y va firmado "
                           + "aparte.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dictamen registrado"),
            @ApiResponse(responseCode = "404", description = "No existe la colocación"),
            @ApiResponse(responseCode = "422", description = "Sin expediente, o rechazo sin motivo")
    })
    @PostMapping("/beneficiaries/placements/{placementId}/identity-review")
    ResponseEntity<Map<String, Object>> reviewIdentity(@PathVariable UUID placementId,
                                                       @RequestBody IdentityReviewRequest req) {
        // El autor sale de la sesión y **nunca del cuerpo**: dejar que el cliente diga quién
        // dictamina permitiría firmar con el nombre de otro, y la firma es todo lo que vuelve
        // evidencia a un dictamen.
        String decidedBy = currentStaffUserId();

        return ResponseEntity.ok(beneficiaryClient.reviewIdentity(
                placementId, req.decision(), decidedBy, req.rejectionReason(), req.verificationSource()));
    }

    /** `decision` = VERIFIED | REJECTED. `verificationSource` nulo = MANUAL. */
    record IdentityReviewRequest(String decision, String rejectionReason, String verificationSource) {}

    private static String currentStaffUserId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Sin sesión");
        }
        return auth.getName();
    }

    // ── Composición ──────────────────────────────────────────────────────────────────────────

    /**
     * Los nombres de las distribuidoras de una página, en una sola llamada.
     *
     * <p>Se deduplica antes de pedir: una bandeja de 50 colocaciones suele tener un puñado de
     * distribuidoras, y pedir 50 ids donde hay 6 distintas gasta ancho de banda y tiempo de base
     * para recibir la misma fila seis veces.
     */
    private Map<UUID, String> resolveDistributorNames(List<Map<String, Object>> rows) {
        Set<UUID> ids = rows.stream()
                .map(r -> uuid(r.get("distributorPartyId")))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) return Map.of();

        try {
            // Se resuelve por `prospectId`, no por `partyId`. El `distributorPartyId` que guarda
            // la colocación es el `sub` del JWT del distribuidor —el mismo id que cartera usa como
            // obligado— y party-service guarda su Party con un id propio distinto. Pedirlo por
            // `partyId` no encuentra nada y la bandeja sale sin nombres, que es justo lo que la
            // mesa necesita para saber a quién llamarle.
            return partyClient.batch(ids, "prospectId").stream()
                    .filter(p -> p.prospectId() != null)
                    .collect(Collectors.toMap(PartyClient.PartyResponse::prospectId,
                            BackofficeViews::fullName, (a, b) -> a));
        } catch (RuntimeException ex) {
            // Un nombre que no resuelve no puede tumbar la bandeja: la colocación y su estado —que
            // es lo que la mesa necesita para trabajar— ya vinieron del dueño. Se pinta sin nombre.
            log.warn("No se pudieron resolver los nombres de {} distribuidoras: {}", ids.size(), ex.toString());
            return Map.of();
        }
    }

    /** Mismo criterio que el lote: el id de la colocación es el del prospecto. */
    private String nameOf(UUID distributorPartyId) {
        if (distributorPartyId == null) return null;
        try {
            return partyClient.batch(List.of(distributorPartyId), "prospectId").stream()
                    .findFirst()
                    .map(BackofficeViews::fullName)
                    .orElse(null);
        } catch (RuntimeException ex) {
            log.warn("No se pudo resolver la distribuidora {}: {}", distributorPartyId, ex.toString());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(Map<String, Object> page) {
        if (page == null) return List.of();
        Object content = page.get("content");
        return content instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static UUID uuid(Object value) {
        if (value == null) return null;
        try {
            return value instanceof UUID u ? u : UUID.fromString(value.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
