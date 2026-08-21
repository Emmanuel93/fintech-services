package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.application.PermissionsService;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient.ApplicationDetailResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient.ProspectDetailResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ScoringClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bandeja de solicitudes y mesa de análisis para el comité/underwriting.
 *
 * <p>Dos vistas:
 *
 * <ul>
 *   <li><b>La bandeja</b> ({@code GET /origination/applications}) — la cola filtrable por
 *       estado, producto, audiencia, fechas y texto. El dominio la pagina; aquí se
 *       presenta como arreglo porque es cola de decisión, no la cartera entera.</li>
 *   <li><b>La ficha de análisis</b> ({@code GET /origination/applications/{id}}) — el
 *       analista ve, en una pantalla, lo que capturó el prospecto, la evaluación de riesgo
 *       que obtuvo el motor (score y detalle regla por regla) y sus créditos vigentes,
 *       para revisar y decidir. Es composición de <b>una</b> llamada por fuente (patrón A:
 *       fan-out de una sola solicitud), no un bucle por filas.</li>
 * </ul>
 *
 * <p>El {@code decidedBy} lo pone el empleado autenticado, no la consola.
 */
@RestController
@Tag(name = "Solicitudes", description = "Bandeja de originación, mesa de análisis y decisión manual")
class OriginationController {

    private static final Logger log = LoggerFactory.getLogger(OriginationController.class);

    /**
     * Roles con facultad de analizar el expediente crediticio. Solo ellos ven el reporte de buró
     * —dato sensible— en la ficha. El BFF es la autoridad de RBAC del backoffice (los roles viajan
     * en X-Roles desde el gateway); el dominio confía en esa decisión.
     */
    private final OriginationClient originationClient;
    private final ScoringClient scoringClient;
    private final CreditPortfolioClient creditPortfolioClient;
    private final PartyClient partyClient;
    private final PermissionsService permissionsService;

    OriginationController(OriginationClient originationClient,
                          ScoringClient scoringClient,
                          CreditPortfolioClient creditPortfolioClient,
                          PartyClient partyClient,
                          PermissionsService permissionsService) {
        this.originationClient = originationClient;
        this.scoringClient = scoringClient;
        this.creditPortfolioClient = creditPortfolioClient;
        this.partyClient = partyClient;
        this.permissionsService = permissionsService;
    }

    @GetMapping("/origination/applications")
    @Operation(summary = "Bandeja de solicitudes",
            description = "Filtros opcionales: estado, producto, audiencia (B2C|B2B2C|B2B), rango de "
                        + "alta (from/to, ISO date) y texto libre sobre el código de promotor.")
    List<Map<String, Object>> queue(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String productType,
            @RequestParam(required = false) String targetAudience,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q) {
        log.info("GET /origination/applications status={} productType={} targetAudience={} from={} to={} q={}",
                status, productType, targetAudience, from, to, q);
        var rows = originationClient.queue(status, productType, targetAudience, from, to, q).stream()
                .map(BackofficeViews::application)
                .toList();
        fillProspectNames(rows);
        return rows;
    }

    /**
     * Rellena el nombre del prospecto de la bandeja, en lote.
     *
     * <p>Una bandeja de UUIDs es inservible: el analista busca por persona. Se
     * resuelven los ids <b>distintos</b> de la página —varias solicitudes del
     * mismo prospecto cuestan una sola consulta— y un fallo individual deja esa
     * fila sin nombre en vez de tumbar la bandeja.
     *
     * <p>Cuando party-service exponga consulta por lote, esto es un solo viaje.
     */
    private void fillProspectNames(List<Map<String, Object>> rows) {
        var ids = rows.stream()
                .map(r -> (String) r.get("prospectId"))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) return;

        Map<String, String> names = new java.util.HashMap<>();
        for (String id : ids) {
            try {
                var p = partyClient.getByProspectId(java.util.UUID.fromString(id));
                if (p == null) continue;
                String full = java.util.stream.Stream.of(p.firstName(), p.lastName1(), p.lastName2())
                        .filter(v -> v != null && !v.isBlank())
                        .collect(java.util.stream.Collectors.joining(" "));
                if (!full.isBlank()) names.put(id, full);
            } catch (Exception ex) {
                log.debug("Sin nombre para el prospecto {}: {}", id, ex.getMessage());
            }
        }
        rows.forEach(r -> r.put("prospectName", names.get((String) r.get("prospectId"))));
    }

    @GetMapping("/origination/applications/{applicationId}")
    @Operation(summary = "Ficha de análisis de una solicitud",
            description = "Compone la solicitud completa, lo que capturó el prospecto, la evaluación "
                        + "de riesgo (score + detalle regla por regla) y sus créditos vigentes. "
                        + "Los bloques secundarios se degradan por separado: si scoring o cartera no "
                        + "responden, la ficha pierde ese bloque, no se cae.")
    ResponseEntity<Map<String, Object>> detail(@PathVariable UUID applicationId) {
        log.info("GET /origination/applications/{}", applicationId);

        // Ancla: la solicitud. Si no existe, propaga 404 —no hay ficha que armar.
        ApplicationDetailResponse app = originationClient.getById(applicationId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application", BackofficeViews.applicationDetail(app));

        UUID prospectId = app == null ? null : app.prospectId();

        // Lo que capturó el sujeto. Se degrada solo: un fallo aquí deja la ficha sin
        // el bloque del capturado, no en blanco.
        ProspectDetailResponse prospect = null;
        if (prospectId != null) {
            try {
                prospect = originationClient.getProspect(prospectId);
            } catch (Exception ex) {
                log.warn("Sin datos capturados del prospecto {}: {}", prospectId, ex.getMessage());
            }
        }
        body.put("prospect", prospect);

        // La evaluación de riesgo obtenida: score y el detalle regla por regla que el
        // analista revisa. Opcional —un prospecto puede no tenerla aún (el cliente la
        // devuelve null en 404).
        Object evaluation = null;
        if (prospectId != null) {
            try {
                evaluation = scoringClient.latestEvaluation(prospectId);
            } catch (Exception ex) {
                log.warn("Sin evaluación de riesgo para el prospecto {}: {}", prospectId, ex.getMessage());
            }
        }
        body.put("evaluation", evaluation);

        // Créditos vigentes del sujeto: contexto para la decisión. UNA llamada por el
        // obligado (no por fila); el nombre sale del capturado, sin ir a party.
        List<Map<String, Object>> existingCredits = List.of();
        if (prospectId != null) {
            try {
                String obligorName = prospect == null ? null : prospect.fullName();
                existingCredits = safe(creditPortfolioClient.listByParty(prospectId)).stream()
                        .map(a -> BackofficeViews.account(a, obligorName))
                        .toList();
            } catch (Exception ex) {
                log.warn("Sin créditos vigentes del prospecto {}: {}", prospectId, ex.getMessage());
            }
        }
        body.put("existingCredits", existingCredits);

        // Reporte de buró: el expediente crediticio. Es lo más sensible de la ficha, así que solo
        // se pide y se reenvía a roles con facultad de análisis; para el resto queda en null (no se
        // pinta el panel). Se degrada solo igual que los demás bloques.
        Object bureauReport = null;
        if (prospectId != null && callerIsAnalyst()) {
            try {
                bureauReport = scoringClient.bureauReport(prospectId);
            } catch (Exception ex) {
                log.warn("Sin reporte de buró para el prospecto {}: {}", prospectId, ex.getMessage());
            }
        }
        body.put("bureauReport", bureauReport);

        return ResponseEntity.ok(body);
    }

    /**
     * ¿Quien pregunta puede analizar el expediente?
     *
     * <p>Se consulta la capacidad y no una lista de roles: la consola esconde el buró y el
     * expediente con `applications.analyze`, y si aquí se comprobara otra cosa las dos podrían
     * separarse sin que nadie se enterara hasta que un analista viera un panel vacío.
     */
    private boolean callerIsAnalyst() {
        return permissionsService.callerHas("applications.analyze");
    }

    @GetMapping("/origination/applications/{applicationId}/documents")
    @Operation(summary = "Expediente de la solicitud",
            description = "Los archivos que entregó el solicitante, con su peso y su tipo. No trae "
                        + "el contenido: eso se pide documento por documento, y sólo del que se abre.")
    List<Map<String, Object>> documents(@PathVariable UUID applicationId) {
        requireAnalystRole();
        UUID prospectId = prospectOf(applicationId);
        return originationClient.documents(prospectId);
    }

    @PutMapping("/origination/applications/{applicationId}/documents/{documentType}/review")
    @Operation(summary = "Dictaminar un documento del expediente",
            description = "El juicio del analista: aprobado o rechazado, con motivo si se rechaza. "
                        + "Volver a subir el archivo **borra el dictamen** — conservarlo aprobaría a "
                        + "ciegas una foto que nadie vio.")
    Map<String, Object> reviewDocument(@PathVariable UUID applicationId,
                                       @PathVariable String documentType,
                                       @RequestBody DocumentReviewRequest req) {
        // El autor sale de la sesión y nunca del cuerpo: la firma es lo que vuelve evidencia a un
        // dictamen, y dejarla en manos del navegador permitiría firmar con el nombre de otro.
        String reviewedBy = currentStaffUserId();
        UUID prospectId = prospectOf(applicationId);
        return originationClient.reviewDocument(prospectId, documentType, req.decision(),
                reviewedBy, req.rejectionReason());
    }

    /** `decision` = APPROVED | REJECTED. Un rechazo exige motivo. */
    record DocumentReviewRequest(String decision, String rejectionReason) {}

    private static String currentStaffUserId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Sin sesión");
        }
        return auth.getName();
    }

    @GetMapping("/origination/applications/{applicationId}/documents/{documentType}/file")
    @Operation(summary = "Ver un documento del expediente",
            description = "Devuelve el archivo con su tipo de contenido, para abrirlo dentro de la "
                        + "ficha. Exige rol de análisis: un documento de identidad es dato personal, "
                        + "no un adjunto cualquiera.")
    ResponseEntity<byte[]> documentFile(@PathVariable UUID applicationId,
                                        @PathVariable String documentType) {
        requireAnalystRole();
        UUID prospectId = prospectOf(applicationId);
        var content = originationClient.documentContent(prospectId, documentType);
        if (content == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sin archivo de " + documentType);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, content.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                // El expediente no se cachea en disco del navegador: es dato personal y la sesión
                // que lo puede ver es la que lo pidió, no la siguiente que abra esa pestaña.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content.bytes());
    }

    /** La solicitud es el ancla de la consola; el expediente cuelga del prospecto. */
    private UUID prospectOf(UUID applicationId) {
        ApplicationDetailResponse app = originationClient.getById(applicationId);
        if (app == null || app.prospectId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "La solicitud no tiene prospecto asociado");
        }
        return app.prospectId();
    }

    private void requireAnalystRole() {
        if (!callerIsAnalyst()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Se requiere un rol de análisis para ver el expediente");
        }
    }

    @PostMapping("/origination/applications/{applicationId}/decision")
    @Operation(summary = "Registrar decisión manual (aprobar/rechazar)")
    ResponseEntity<Void> decide(@PathVariable UUID applicationId, @RequestBody DecisionRequest request) {
        log.info("POST /origination/applications/{}/decision approved={}", applicationId, request.approved());
        originationClient.decide(applicationId, request.approved(), request.rejectionReason());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/origination/applications/{applicationId}/request-documents")
    @Operation(summary = "Pedir documentos adicionales (pasa la solicitud a PENDING_DOCUMENTS)")
    ResponseEntity<Void> requestDocuments(@PathVariable UUID applicationId,
                                          @RequestBody DocumentsRequest request) {
        log.info("POST /origination/applications/{}/request-documents", applicationId);
        originationClient.requestDocuments(applicationId, request.note());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/origination/applications/{applicationId}/documents-received")
    @Operation(summary = "Marcar documentos recibidos (vuelve a revisión)")
    ResponseEntity<Void> documentsReceived(@PathVariable UUID applicationId) {
        log.info("POST /origination/applications/{}/documents-received", applicationId);
        originationClient.markDocumentsReceived(applicationId);
        return ResponseEntity.ok().build();
    }

    private static <T> List<T> safe(List<T> xs) { return xs == null ? List.of() : xs; }

    /** Lo que teclea el comité: aprobar/rechazar + motivo (obligatorio si rechaza). */
    record DecisionRequest(boolean approved, String rejectionReason) {}

    /** Qué documentos se piden al sujeto (viaja en la notificación). */
    record DocumentsRequest(String note) {}
}
