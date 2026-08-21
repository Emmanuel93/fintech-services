package com.fintech.audit.infrastructure.adapter.in.api;

import com.fintech.audit.application.service.AuditService;
import com.fintech.audit.application.service.DocumentArchiveService;
import com.fintech.audit.application.service.UIFReportService;
import com.fintech.audit.infrastructure.adapter.in.api.dto.AuditEntryDetailResponse;
import com.fintech.audit.infrastructure.adapter.in.api.dto.AuditEntryResponse;
import com.fintech.audit.infrastructure.adapter.in.api.dto.DocumentFileRefResponse;
import com.fintech.audit.infrastructure.adapter.in.api.dto.RecordAccessRequest;
import com.fintech.audit.infrastructure.adapter.in.api.dto.UIFReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Log inmutable regulatorio — solo roles AUDITOR/REGULATOR/ADMIN")
@SecurityRequirement(name = "bearerAuth")
class AuditController {

    private final AuditService auditService;
    private final DocumentArchiveService documentArchiveService;
    private final UIFReportService uifReportService;

    AuditController(AuditService auditService,
                    DocumentArchiveService documentArchiveService,
                    UIFReportService uifReportService) {
        this.auditService          = auditService;
        this.documentArchiveService = documentArchiveService;
        this.uifReportService      = uifReportService;
    }

    @Operation(summary = "Registrar un acceso (pantalla, búsqueda, descarga, consumo). Lo llama el canal por cada request.")
    @PostMapping("/access")
    ResponseEntity<Void> recordAccess(
            @Valid @RequestBody RecordAccessRequest request,
            @RequestHeader(value = "X-Channel", required = false) String channel) {
        auditService.recordAccess(request.toCommand(currentPrincipal(), sourceOf(channel)));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /**
     * De qué servicio viene el reporte, cuando el canal no lo dice de su puño y letra.
     *
     * <p>Se deduce del {@code X-Channel} que ya viaja con la identidad. Antes el origen iba fijo a
     * {@code channel-backoffice-service} para toda entrada de acceso, así que los accesos desde la
     * app aparecían atribuidos a una consola a la que un cliente no tiene entrada. Ante un canal
     * desconocido se dice justo eso, y no un servicio inventado.
     */
    private static String sourceOf(String channel) {
        if (channel == null || channel.isBlank()) return "channel-unknown";
        return switch (channel.trim().toUpperCase()) {
            case "BACKOFFICE" -> "channel-backoffice-service";
            case "MOBILE"     -> "channel-mobile-service";
            default           -> "channel-" + channel.trim().toLowerCase();
        };
    }

    @Operation(summary = "Bitácora filtrada — devuelve lo más reciente primero",
               description = "El parámetro `limit` acota el número de entradas (por omisión 200, "
                           + "máximo 1000). Para ir más atrás en el tiempo se usan `from` y `to`, "
                           + "no un número de página: sobre una bitácora que crece cada segundo, "
                           + "las páginas darían resultados inconsistentes entre una y otra.")
    @GetMapping("/entries")
    ResponseEntity<List<AuditEntryResponse>> listEntries(
            @RequestParam(required = false) String partyId,
            @RequestParam(required = false) String aggregateId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String actorIp,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "0") int limit) {

        Instant effectiveFrom = from != null ? from : Instant.EPOCH;
        Instant effectiveTo   = to   != null ? to   : Instant.now();

        List<AuditEntryResponse> result;
        if (partyId != null) {
            result = auditService.findByPartyId(partyId, effectiveFrom, effectiveTo, limit)
                    .stream().map(AuditEntryResponse::from).toList();
        } else if (aggregateId != null) {
            result = auditService.findByAggregateId(aggregateId, limit)
                    .stream().map(AuditEntryResponse::from).toList();
        } else if (eventType != null) {
            result = auditService.findByEventType(eventType, effectiveFrom, effectiveTo, limit)
                    .stream().map(AuditEntryResponse::from).toList();
        } else if (actor != null) {
            result = auditService.findByActor(actor, effectiveFrom, effectiveTo, limit)
                    .stream().map(AuditEntryResponse::from).toList();
        } else if (actorIp != null) {
            result = auditService.findByActorIp(actorIp, effectiveFrom, effectiveTo, limit)
                    .stream().map(AuditEntryResponse::from).toList();
        } else if (action != null) {
            result = auditService.findByAction(action, effectiveFrom, effectiveTo, limit)
                    .stream().map(AuditEntryResponse::from).toList();
        } else if (category != null) {
            result = auditService.findByCategory(category, effectiveFrom, effectiveTo, limit)
                    .stream().map(AuditEntryResponse::from).toList();
        } else {
            result = auditService.findByDateRange(effectiveFrom, effectiveTo, limit)
                    .stream().map(AuditEntryResponse::from).toList();
        }
        return ResponseEntity.ok(result);
    }

    /** El staffUserId autenticado (inyectado por el gateway), como respaldo si el canal no lo mandó en el cuerpo. */
    private static String currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? String.valueOf(auth.getPrincipal()) : null;
    }

    @Operation(summary = "Detalle de una entrada de auditoría (incluye payload)")
    @GetMapping("/entries/{entryId}")
    ResponseEntity<AuditEntryDetailResponse> getEntry(@PathVariable UUID entryId) {
        return ResponseEntity.ok(AuditEntryDetailResponse.from(auditService.findById(entryId)));
    }

    @Operation(summary = "Documentos archivados de un party")
    @GetMapping("/parties/{partyId}/documents")
    ResponseEntity<List<DocumentFileRefResponse>> listDocuments(@PathVariable UUID partyId) {
        return ResponseEntity.ok(
                documentArchiveService.findByPartyId(partyId)
                        .stream().map(DocumentFileRefResponse::from).toList());
    }

    @Operation(summary = "Reportes UIF de un party")
    @GetMapping("/parties/{partyId}/uif-reports")
    ResponseEntity<List<UIFReportResponse>> listUIFReports(@PathVariable UUID partyId) {
        return ResponseEntity.ok(
                uifReportService.findByPartyId(partyId)
                        .stream().map(UIFReportResponse::from).toList());
    }

    @Operation(summary = "Expediente regulatorio completo de un party")
    @GetMapping("/parties/{partyId}/expedition")
    ResponseEntity<Object> getExpedition(@PathVariable UUID partyId) {
        return ResponseEntity.ok(java.util.Map.of(
                "auditEntries", auditService.findByPartyId(partyId.toString(), Instant.EPOCH, Instant.now(),
                                AuditService.MAX_LIMIT)
                        .stream().map(AuditEntryResponse::from).toList(),
                "documents", documentArchiveService.findByPartyId(partyId)
                        .stream().map(DocumentFileRefResponse::from).toList(),
                "uifReports", uifReportService.findByPartyId(partyId)
                        .stream().map(UIFReportResponse::from).toList()
        ));
    }
}
