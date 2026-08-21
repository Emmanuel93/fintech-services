package com.fintech.origination.infrastructure.adapter.in.api;

import com.fintech.origination.application.RecordApprovalDecisionCommand;
import com.fintech.origination.application.RequestDocumentsCommand;
import com.fintech.origination.application.port.in.FindCreditApplicationUseCase;
import com.fintech.origination.application.port.in.RecordApprovalDecisionUseCase;
import com.fintech.origination.application.port.in.RequestDocumentsUseCase;
import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.ProductType;
import com.fintech.origination.domain.TargetAudience;
import com.fintech.origination.infrastructure.adapter.in.api.dto.CreditApplicationResponse;
import com.fintech.origination.infrastructure.adapter.in.api.dto.RecordManualDecisionRequest;
import com.fintech.origination.infrastructure.adapter.in.api.dto.RequestDocumentsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Endpoints for underwriters and committee members to record manual credit decisions.
 * All operations require JWT authentication (UNDERWRITER or COMMITTEE role).
 */
@RestController
@RequestMapping("/api/v1/origination/underwriting")
@Tag(name = "Underwriting", description = "Manual and committee credit approval decisions")
class UnderwritingController {

    private static final Logger log = LoggerFactory.getLogger(UnderwritingController.class);

    private final RecordApprovalDecisionUseCase decisionUseCase;
    private final FindCreditApplicationUseCase findUseCase;
    private final RequestDocumentsUseCase requestDocumentsUseCase;

    UnderwritingController(RecordApprovalDecisionUseCase decisionUseCase,
                            FindCreditApplicationUseCase findUseCase,
                            RequestDocumentsUseCase requestDocumentsUseCase) {
        this.decisionUseCase = decisionUseCase;
        this.findUseCase     = findUseCase;
        this.requestDocumentsUseCase = requestDocumentsUseCase;
    }

    @PostMapping("/applications/{applicationId}/decision")
    @Operation(
            summary = "Record manual or committee credit decision",
            description = "Transitions UNDER_MANUAL_REVIEW or COMMITTEE_REVIEW → APPROVED | REJECTED. "
                        + "Publishes origination.application-approved / origination.application-rejected. "
                        + "rejectionReason is mandatory when approved=false (UW-05 / CONDUSEF).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Decision recorded — application updated"),
        @ApiResponse(responseCode = "400", description = "Validation error or missing rejectionReason"),
        @ApiResponse(responseCode = "404", description = "Application not found"),
        @ApiResponse(responseCode = "422", description = "Application not in UNDER_MANUAL_REVIEW or COMMITTEE_REVIEW")
    })
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<CreditApplicationResponse> recordDecision(
            @PathVariable UUID applicationId,
            @Valid @RequestBody RecordManualDecisionRequest request) {

        log.info("Manual decision applicationId={} decidedBy={} approved={}",
                applicationId, request.decidedBy(), request.approved());

        if (!request.approved()
                && (request.rejectionReason() == null || request.rejectionReason().isBlank())) {
            return ResponseEntity.badRequest().build();
        }

        decisionUseCase.record(new RecordApprovalDecisionCommand(
                applicationId,
                request.decidedBy(),
                request.approved(),
                request.rejectionReason()));

        return ResponseEntity.ok(
                CreditApplicationResponse.from(findUseCase.getById(applicationId)));
    }

    @PostMapping("/applications/{applicationId}/request-documents")
    @Operation(
            summary = "Pedir documentos adicionales (E6)",
            description = "Transiciona UNDER_MANUAL_REVIEW | COMMITTEE_REVIEW → PENDING_DOCUMENTS con un "
                        + "plazo (TTL) y publica origination.documents-requested. Vencido el plazo sin "
                        + "documentos, el barrido horario cancela la solicitud.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Documentos solicitados — solicitud en PENDING_DOCUMENTS"),
        @ApiResponse(responseCode = "400", description = "Falta la nota o el solicitante"),
        @ApiResponse(responseCode = "404", description = "Solicitud no encontrada"),
        @ApiResponse(responseCode = "422", description = "La solicitud no está en revisión humana")
    })
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<CreditApplicationResponse> requestDocuments(
            @PathVariable UUID applicationId,
            @Valid @RequestBody RequestDocumentsRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        log.info("Request documents applicationId={} by={}", applicationId, request.decidedBy());
        requestDocumentsUseCase.requestDocuments(new RequestDocumentsCommand(
                applicationId, request.decidedBy(), request.note(), correlationId));
        return ResponseEntity.ok(CreditApplicationResponse.from(findUseCase.getById(applicationId)));
    }

    @PostMapping("/applications/{applicationId}/documents-received")
    @Operation(
            summary = "Marcar documentos recibidos (E6)",
            description = "El sujeto entregó los documentos: PENDING_DOCUMENTS → UNDER_MANUAL_REVIEW.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "De vuelta en revisión"),
        @ApiResponse(responseCode = "404", description = "Solicitud no encontrada"),
        @ApiResponse(responseCode = "422", description = "La solicitud no está en PENDING_DOCUMENTS")
    })
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<CreditApplicationResponse> documentsReceived(@PathVariable UUID applicationId) {
        log.info("Documents received applicationId={}", applicationId);
        requestDocumentsUseCase.markDocumentsReceived(applicationId);
        return ResponseEntity.ok(CreditApplicationResponse.from(findUseCase.getById(applicationId)));
    }

    @GetMapping("/applications")
    @Operation(
            summary = "Bandeja de solicitudes en revisión humana (paginada)",
            description = "Solicitudes en UNDER_MANUAL_REVIEW o COMMITTEE_REVIEW. Filtros opcionales por "
                        + "producto, audiencia (B2C|B2B2C|B2B), prospecto, rango de fechas y código de "
                        + "promotor. Sin prospectId es la cola completa de revisión.")
    @ApiResponse(responseCode = "200", description = "Applications returned")
    @SecurityRequirement(name = "bearerAuth")
    Page<CreditApplicationResponse> listAwaitingDecision(
            @RequestParam(required = false) ProductType productType,
            @RequestParam(required = false) TargetAudience targetAudience,
            @RequestParam(required = false) UUID prospectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {

        Pageable pageable = PageRequest.of(Math.max(0, page),
                Math.min(Math.max(1, size), ApplicationQuerySupport.MAX_PAGE_SIZE),
                ApplicationQuerySupport.parseSort(sort));
        // La bandeja de underwriting es, por definición, lo que espera decisión humana.
        Collection<ApplicationStatus> reviewStatuses =
                List.of(ApplicationStatus.UNDER_MANUAL_REVIEW, ApplicationStatus.COMMITTEE_REVIEW);
        Collection<ProductType> audienceTypes =
                targetAudience != null ? targetAudience.productTypes() : null;

        return findUseCase.search(reviewStatuses, productType, audienceTypes, prospectId,
                        ApplicationQuerySupport.startOfDay(from),
                        ApplicationQuerySupport.startOfNextDay(to), q, pageable)
                .map(CreditApplicationResponse::from);
    }
}
