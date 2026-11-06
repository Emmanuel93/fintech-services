package com.fintech.beneficiary.infrastructure.adapter.in.api;

import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.PlacementQueryRepository;
import com.fintech.beneficiary.application.port.out.PlacementTransitionRepository;
import com.fintech.beneficiary.application.port.out.IdentityVerificationGateway;
import com.fintech.beneficiary.domain.IdentityDecision;
import com.fintech.beneficiary.application.service.KycPolicyResolver;
import com.fintech.beneficiary.domain.VerificationSource;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementStatus;
import com.fintech.beneficiary.domain.PlacementTransition;
import com.fintech.beneficiary.infrastructure.adapter.in.api.dto.BackofficePlacementResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.beneficiary.application.KycData;
import com.fintech.beneficiary.application.port.out.ConsentRecordRepository;
import com.fintech.beneficiary.application.port.out.KycDocumentRepository;
import com.fintech.beneficiary.application.port.out.KycSessionRepository;
import com.fintech.beneficiary.domain.KycDocumentSummary;
import com.fintech.beneficiary.domain.KycDocumentType;
import com.fintech.beneficiary.domain.KycSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import com.fintech.shared.time.BusinessClock;

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
    private final KycPolicyResolver kycPolicyResolver;
    private final KycDocumentRepository kycDocuments;
    private final KycSessionRepository kycSessions;
    private final ConsentRecordRepository consentRecords;
    private final ObjectMapper objectMapper;

    BackofficePlacementController(PlacementQueryRepository placementQuery,
                                  PlacementLifecycleUseCase placementLifecycle,
                                  PlacementTransitionRepository transitions,
                                  IdentityVerificationGateway verificationGateway,
                                  KycPolicyResolver kycPolicyResolver,
                                  KycDocumentRepository kycDocuments,
                                  KycSessionRepository kycSessions,
                                  ConsentRecordRepository consentRecords,
                                  ObjectMapper objectMapper) {
        this.placementQuery      = placementQuery;
        this.placementLifecycle  = placementLifecycle;
        this.transitions         = transitions;
        this.verificationGateway = verificationGateway;
        this.kycPolicyResolver   = kycPolicyResolver;
        this.kycDocuments        = kycDocuments;
        this.kycSessions         = kycSessions;
        this.consentRecords      = consentRecords;
        this.objectMapper        = objectMapper;
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

        Instant now = BusinessClock.current().now();

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
        Instant now = BusinessClock.current().now();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("placement", BackofficePlacementResponse.from(placement, now));
        body.put("timeline", transitions.findByPlacementIdOrderByOccurredAtAsc(placementId).stream()
                .map(BackofficePlacementController::timelineEntry).toList());
        body.put("identityEvidence", identityEvidence(placementId));
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Ver un documento del KYC web",
               description = "Los bytes con su tipo de contenido, para abrirlo en la ficha (`inline`).")
    @ApiResponse(responseCode = "404", description = "La beneficiaria no entregó ese documento")
    @GetMapping("/{placementId}/documents/{documentType}/file")
    ResponseEntity<byte[]> document(@PathVariable UUID placementId,
                                    @PathVariable KycDocumentType documentType) {
        return kycDocuments.findByPlacementIdAndDocumentType(placementId, documentType)
                .map(d -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(d.getContentType()))
                        // `inline`: se abre junto a lo capturado. Quien dictamina compara la foto
                        // contra los datos; no colecciona credenciales en su carpeta de descargas.
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "inline; filename=\"" + documentType.name().toLowerCase() + "\"")
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(d.getContent()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Lo que el analista compara para dictaminar: las fotos, lo que ella capturó y lo que firmó.
     *
     * <p>No hay proveedor —ni OCR, ni prueba de vida, ni RENAPO—: la evidencia es la captura misma,
     * y el cotejo lo hace una persona. Sin capturas se declara ausente con su motivo en vez de
     * omitirse, para que la consola diga «pendiente» y no dibuje una sección vacía que parece un
     * error.
     */
    private Map<String, Object> identityEvidence(UUID placementId) {
        List<KycDocumentSummary> docs = kycDocuments.summariesFor(placementId);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("available", !docs.isEmpty());
        if (docs.isEmpty()) {
            evidence.put("reason", "La beneficiaria todavía no ha entregado documentos en la web de KYC");
        }
        evidence.put("documents", docs.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("documentType", d.documentType().name());
            m.put("contentType", d.contentType());
            m.put("sizeBytes", d.sizeBytes());
            m.put("uploadedAt", d.uploadedAt());
            return m;
        }).toList());
        evidence.put("captured", kycSessions.findFirstByPlacementIdOrderByCreatedAtDesc(placementId)
                .map(KycSession::getCapturedData)
                .map(this::capturedView)
                .orElse(null));
        evidence.put("consents", consentRecords.findByPlacementIdOrderByAcceptedAtAsc(placementId).stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("consentType", c.getConsentType().name());
                    m.put("textVersion", c.getTextVersion());
                    m.put("acceptedAt", c.getAcceptedAt());
                    m.put("ipAddress", c.getIpAddress());
                    return m;
                }).toList());
        return evidence;
    }

    /**
     * Lo capturado, con la CLABE enmascarada. Para cotejar identidad basta saber que la hay y a
     * qué banco va; los 18 dígitos completos son un dato de pago, no de identidad.
     */
    private Map<String, Object> capturedView(String json) {
        KycData d;
        try {
            d = objectMapper.readValue(json, KycData.class);
        } catch (JsonProcessingException e) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("firstName", d.firstName());
        m.put("lastName1", d.lastName1());
        m.put("lastName2", d.lastName2());
        m.put("curp", d.curp());
        m.put("rfc", d.rfc());
        m.put("dateOfBirth", d.dateOfBirth());
        m.put("occupation", d.occupation());
        m.put("monthlyIncome", d.monthlyIncome());
        m.put("street", d.street());
        m.put("exteriorNumber", d.exteriorNumber());
        m.put("neighborhood", d.neighborhood());
        m.put("municipality", d.municipality());
        m.put("city", d.city());
        m.put("state", d.state());
        m.put("postalCode", d.postalCode());
        m.put("clabeMasked", maskClabe(d.clabe()));
        return m;
    }

    static String maskClabe(String clabe) {
        if (clabe == null || clabe.length() < 4) return null;
        return clabe.substring(0, 3) + " •••• •••• ••• " + clabe.substring(clabe.length() - 4);
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
        body.put("placement", BackofficePlacementResponse.from(placement, BusinessClock.current().now()));
        // La estrategia es la del producto de ESTA colocación (F3b): ya no hay una global.
        body.put("verificationStrategy",
                verificationGateway.describeStrategy(kycPolicyResolver.resolve(placement)));
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
