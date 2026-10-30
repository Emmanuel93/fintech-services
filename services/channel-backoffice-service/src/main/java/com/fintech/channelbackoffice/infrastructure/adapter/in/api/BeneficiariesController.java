package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.BeneficiaryClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ComplianceClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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
    private final ComplianceClient complianceClient;
    private final StaffIdentityResolver staffIdentities;

    BeneficiariesController(BeneficiaryClient beneficiaryClient, PartyClient partyClient,
                            ComplianceClient complianceClient, StaffIdentityResolver staffIdentities) {
        this.beneficiaryClient = beneficiaryClient;
        this.partyClient       = partyClient;
        this.complianceClient  = complianceClient;
        this.staffIdentities   = staffIdentities;
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
        // Se firma con el correo, como en la mesa de cumplimiento: el expediente se lee meses
        // después, y «Firmó: 8f3c…» no le dice a nadie quién comprobó la identidad. Si identity no
        // resuelve el correo, se firma con el id — un dictamen nunca se queda sin autor.
        String userId = currentStaffUserId();
        String decidedBy = staffIdentities.resolve(userId)
                .map(StaffIdentityResolver.StaffIdentity::email)
                .filter(e -> e != null && !e.isBlank())
                .orElse(userId);

        Map<String, Object> reviewed = beneficiaryClient.reviewIdentity(
                placementId, req.decision(), decidedBy, req.rejectionReason(), req.verificationSource());

        Map<String, Object> body = new LinkedHashMap<>(reviewed);
        body.put("complianceSync", mirrorToCompliance(placementId, reviewed, req, decidedBy));
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Ver una pieza de evidencia de la beneficiaria",
               description = "INE (frente/reverso) o selfie, tal como la capturó en la web de su liga. "
                           + "Dato personal: no se cachea.")
    @GetMapping("/beneficiaries/placements/{placementId}/documents/{documentType}/file")
    ResponseEntity<byte[]> documentFile(@PathVariable UUID placementId, @PathVariable String documentType) {
        var content = beneficiaryClient.documentContent(placementId, documentType);
        if (content == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sin archivo de " + documentType);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, content.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content.bytes());
    }

    /**
     * Lleva el mismo dictamen al veredicto de la persona en compliance.
     *
     * <h2>Por qué</h2>
     *
     * La identidad de una persona se comprueba una vez y vale para todo — es la decisión del
     * análisis de KYC/AML: un solo proceso para quien quede registrado. Pero la beneficiaria tenía
     * dos expedientes: el de la colocación, que la mesa sí firmaba, y el de compliance, que se
     * quedaba en {@code IN_REVIEW} para siempre porque nadie lo veía. Y es el de compliance el que
     * consulta la compuerta de disposición: sin este espejo, su identidad comprobada nunca llegaba
     * al camino del dinero.
     *
     * <p>Una sola firma, en las dos partes, con el mismo autor. Si compliance no contesta, el
     * dictamen de la colocación ya quedó firmado y se informa en {@code complianceSync} en vez de
     * fallar: la mesa no tiene que repetirlo, y lo pendiente queda visible en la mesa de
     * cumplimiento, donde se puede terminar.
     */
    private Map<String, Object> mirrorToCompliance(UUID placementId, Map<String, Object> reviewed,
                                                   IdentityReviewRequest req, String decidedBy) {
        Map<String, Object> sync = new LinkedHashMap<>();
        try {
            UUID partyId = reviewed.get("placement") instanceof Map<?, ?> p
                    ? uuid(p.get("beneficiaryPartyId")) : null;
            UUID prospectId = partyId == null ? null
                    : partyClient.batch(List.of(partyId), "partyId").stream()
                            .findFirst().map(PartyClient.PartyResponse::prospectId).orElse(null);
            if (prospectId == null) {
                sync.put("done", false);
                sync.put("reason", "La beneficiaria todavía no tiene expediente de persona");
                return sync;
            }
            var verification = complianceClient.byProspect(prospectId).orElse(null);
            if (verification == null) {
                sync.put("done", false);
                sync.put("reason", "Compliance aún no la evaluó");
                return sync;
            }
            String status = String.valueOf(verification.get("identityStatus"));
            if (!"PENDING".equals(status) && !"IN_REVIEW".equals(status)) {
                // Ya dictaminada en la mesa de cumplimiento: no se pisa una firma con otra.
                sync.put("done", true);
                sync.put("identityStatus", status);
                return sync;
            }
            String notes = "VERIFIED".equals(req.decision())
                    ? "Identidad comprobada en la mesa de KYC de colocaciones (colocación " + placementId
                      + "): INE y selfie de la liga cotejadas a mano, sin proveedor biométrico."
                    : "Identidad rechazada en la mesa de KYC de colocaciones (colocación " + placementId
                      + "): " + req.rejectionReason();
            var after = complianceClient.decideIdentity(
                    uuid(verification.get("verificationId")), req.decision(), decidedBy, notes);
            sync.put("done", true);
            sync.put("identityStatus", after == null ? null : after.get("identityStatus"));
            return sync;
        } catch (RuntimeException ex) {
            log.warn("El dictamen de la colocación {} no llegó a compliance: {}", placementId, ex.toString());
            sync.put("done", false);
            sync.put("reason", "Compliance no respondió: el dictamen quedó en la colocación");
            return sync;
        }
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
