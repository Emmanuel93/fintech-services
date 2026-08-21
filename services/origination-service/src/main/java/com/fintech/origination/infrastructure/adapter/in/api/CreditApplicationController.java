package com.fintech.origination.infrastructure.adapter.in.api;

import com.fintech.origination.application.StartCreditApplicationCommand;
import com.fintech.origination.application.StartCreditApplicationResult;
import com.fintech.origination.application.port.in.FindCreditApplicationUseCase;
import com.fintech.origination.application.port.in.StartCreditApplicationUseCase;
import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.ProductType;
import com.fintech.origination.domain.TargetAudience;
import com.fintech.origination.infrastructure.adapter.in.api.dto.CreditApplicationResponse;
import com.fintech.origination.infrastructure.adapter.in.api.dto.StartCreditApplicationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/origination/applications")
@Tag(name = "Credit Applications", description = "Product selection and credit application lifecycle")
class CreditApplicationController {

    private static final Logger log = LoggerFactory.getLogger(CreditApplicationController.class);

    private final StartCreditApplicationUseCase startUseCase;
    private final FindCreditApplicationUseCase findUseCase;

    CreditApplicationController(StartCreditApplicationUseCase startUseCase,
                               FindCreditApplicationUseCase findUseCase) {
        this.startUseCase = startUseCase;
        this.findUseCase  = findUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Start a credit application (select a product)",
            description = "An onboarded prospect selects a product. Emits ScoreRequested, which " +
                          "triggers the scoring decision engine for (prospectType, productType).",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Application started (PENDING_SCORING)"),
                    @ApiResponse(responseCode = "400", description = "Validation error"),
                    @ApiResponse(responseCode = "404", description = "Prospect not found (not onboarded)"),
                    @ApiResponse(responseCode = "409", description = "Active application already exists for this product")
            })
    CreditApplicationResponse start(
            @Valid @RequestBody StartCreditApplicationRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        log.info("Credit application request prospectId={} productType={} promoterCode={}",
                request.prospectId(), request.productType(), request.promoterCode());

        StartCreditApplicationCommand command = new StartCreditApplicationCommand(
                request.prospectId(),
                request.productType(),
                request.requestedAmount(),
                request.requestedTerm(),
                correlationId,
                request.promoterCode());

        StartCreditApplicationResult result = startUseCase.start(command);

        return switch (result) {
            case StartCreditApplicationResult.ApplicationStarted r -> {
                log.info("Credit application started applicationId={} status={}",
                        r.applicationId(), r.status());
                yield CreditApplicationResponse.from(findUseCase.getById(r.applicationId()));
            }
        };
    }

    @GetMapping("/{applicationId}")
    @Operation(summary = "Get a credit application by id")
    ResponseEntity<CreditApplicationResponse> getById(@PathVariable UUID applicationId) {
        return ResponseEntity.ok(CreditApplicationResponse.from(findUseCase.getById(applicationId)));
    }

    @GetMapping
    @Operation(summary = "Bandeja paginada de solicitudes (backoffice)",
            description = "Todos los filtros opcionales: estado, producto, audiencia (B2C|B2B2C|B2B), "
                        + "prospecto, rango de fechas de alta (from/to, to exclusivo) y texto libre "
                        + "sobre el código de promotor. Sin prospectId es una bandeja por población.")
    Page<CreditApplicationResponse> list(
            @RequestParam(required = false) ApplicationStatus status,
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
        Collection<ApplicationStatus> statuses = status != null ? List.of(status) : null;
        Collection<ProductType> audienceTypes =
                targetAudience != null ? targetAudience.productTypes() : null;

        return findUseCase.search(statuses, productType, audienceTypes, prospectId,
                        ApplicationQuerySupport.startOfDay(from),
                        ApplicationQuerySupport.startOfNextDay(to), q, pageable)
                .map(CreditApplicationResponse::from);
    }
}
