package com.fintech.beneficiary.infrastructure.adapter.in.api;

import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.PlacementQueryRepository;
import com.fintech.beneficiary.application.port.out.PlacementTransitionRepository;
import com.fintech.beneficiary.application.port.out.IdentityVerificationGateway;
import com.fintech.beneficiary.domain.IdentityDecision;
import com.fintech.beneficiary.domain.VerificationSource;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementStatus;
import com.fintech.beneficiary.domain.PlacementTransition;
import com.fintech.beneficiary.infrastructure.adapter.in.api.dto.BackofficePlacementResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * La bandeja transversal de colocaciones: la consulta de la mesa de KYC.
 *
 * <p>Vive aparte de {@link PlacementController} porque responde una pregunta opuesta. Aquél sirve
 * a la app y deriva la distribuidora del token —nunca de un parámetro—, de modo que un
 * distribuidor sólo ve lo suyo. Ésta cruza todas las distribuidoras, así que <b>no puede colgar de
 * la misma ruta</b>: si compartieran raíz, una equivocación al declarar la seguridad convertiría
 * la consulta de la app en una fuga de la cartera ajena.
 *
 * <p>Sólo la alcanza el BFF de backoffice, que ya resolvió quién pregunta y con qué capacidad. Este
 * servicio no vuelve a decidirlo: confía en el canal, igual que el resto del monorepo.
 */
@RestController
@RequestMapping("/api/v1/backoffice/placements")
@Tag(name = "Colocaciones · backoffice", description = "Bandeja transversal para la mesa de KYC")
class BackofficePlacementController {

    /** Tope de página. Una bandeja se navega; no se descarga entera. */
    private static final int MAX_PAGE_SIZE = 200;

    private final PlacementQueryRepository placementQuery;
    private final PlacementLifecycleUseCase placementLifecycle;
    private final PlacementTransitionRepository transitions;
    private final IdentityVerificationGateway verificationGateway;

    BackofficePlacementController(PlacementQueryRepository placementQuery,
                                  PlacementLifecycleUseCase placementLifecycle,
                                  PlacementTransitionRepository transitions,
                                  IdentityVerificationGateway verificationGateway) {
        this.placementQuery      = placementQuery;
        this.placementLifecycle  = placementLifecycle;
        this.transitions         = transitions;
        this.verificationGateway = verificationGateway;
    }

    @Operation(summary = "Bandeja de colocaciones — todos los filtros opcionales",
               description = "Cruza todas las distribuidoras. `identityDecision` filtra por el veredicto "
                           + "de identidad, que es lo que detiene un depósito; el score nunca "
                           + "bloquea. `stalledDays` es el SLA: sólo lo que no se mueve desde hace "
                           + "N días.")
    @ApiResponse(responseCode = "200", description = "Página de colocaciones")
    @GetMapping
    ResponseEntity<Map<String, Object>> search(
            @RequestParam(required = false) List<UUID> distributorPartyId,
            @RequestParam(required = false) List<PlacementStatus> status,
            @RequestParam(required = false) List<IdentityDecision> identityDecision,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer stalledDays,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Instant now = Instant.now();

        // El veredicto de identidad ya es columna propia, así que se filtra directo. Antes había
        // que traducirlo a los estados que lo producían —porque se derivaba— y esa traducción
        // desaparece con la derivación.
        Page<Placement> result = placementQuery.search(
                distributorPartyId,
                status,
                identityDecision,
                from,
                to,
                stalledDays == null ? now : now.minus(stalledDays, ChronoUnit.DAYS),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.DESC, "updatedAt")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", result.getContent().stream()
                .map(p -> BackofficePlacementResponse.from(p, now)).toList());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Ficha de una colocación, con su bitácora de estados")
    @GetMapping("/{placementId}")
    ResponseEntity<Map<String, Object>> detail(@PathVariable UUID placementId) {
        Placement placement = placementLifecycle.findById(placementId);
        Instant now = Instant.now();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("placement", BackofficePlacementResponse.from(placement, now));
        body.put("timeline", transitions.findByPlacementIdOrderByOccurredAtAsc(placementId).stream()
                .map(BackofficePlacementController::timelineEntry).toList());
        // La captura granular de identidad —coincidencia facial, prueba de vida, cotejo
        // INE/RENAPO— todavía no existe en este servicio. Se declara ausente en vez de omitirse
        // para que la consola pueda decir «pendiente» en lugar de dibujar una sección vacía que
        // parece un error.
        body.put("identityEvidence", Map.of(
                "available", false,
                "reason", "La captura de identidad del beneficiario aún no está implementada"));
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Dictaminar la identidad de una beneficiaria",
               description = "El juicio de la mesa de KYC. **Es lo único que habilita el depósito**: "
                           + "sin identidad comprobada, la distribuidora no puede aprobar. No decide "
                           + "sobre el riesgo —eso es de ella y va firmado aparte—.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dictamen registrado"),
            @ApiResponse(responseCode = "404", description = "No existe la colocación"),
            @ApiResponse(responseCode = "422", description = "Sin autor, sin expediente, o rechazo sin motivo")
    })
    @PostMapping("/{placementId}/identity-review")
    ResponseEntity<Map<String, Object>> reviewIdentity(@PathVariable UUID placementId,
                                                       @Valid @RequestBody IdentityReviewRequest req) {
        // El origen lo declara quien dictamina. Hoy siempre MANUAL —no hay proveedor—, pero cuando
        // lo haya, quien resuelve encima de una señal automática tiene que poder decir que fue una
        // escalación y no una revisión de cero: es lo que después permite calibrar los umbrales.
        VerificationSource source = req.verificationSource() == null
                ? VerificationSource.MANUAL
                : req.verificationSource();

        Placement placement = placementLifecycle.reviewIdentity(
                placementId, req.decision(), source, req.decidedBy(), req.rejectionReason());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("placement", BackofficePlacementResponse.from(placement, Instant.now()));
        body.put("verificationStrategy", verificationGateway.describeStrategy());
        return ResponseEntity.ok(body);
    }

    /**
     * @param verificationSource opcional. Nulo = {@code MANUAL}, que es el caso mientras no haya
     *                           proveedor de KYC contratado.
     */
    record IdentityReviewRequest(@NotNull IdentityDecision decision,
                                 @NotBlank String decidedBy,
                                 String rejectionReason,
                                 VerificationSource verificationSource) {}

    private static Map<String, Object> timelineEntry(PlacementTransition t) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("fromStatus", t.getFromStatus() == null ? null : t.getFromStatus().name());
        e.put("toStatus", t.getToStatus().name());
        e.put("actor", t.getActor().name());
        e.put("actorPartyId", t.getActorPartyId());
        e.put("reason", t.getReason());
        e.put("occurredAt", t.getOccurredAt());
        return e;
    }

}
