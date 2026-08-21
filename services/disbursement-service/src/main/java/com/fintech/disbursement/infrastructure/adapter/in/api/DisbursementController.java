package com.fintech.disbursement.infrastructure.adapter.in.api;

import com.fintech.disbursement.application.RequestDisbursementCommand;
import com.fintech.disbursement.application.port.in.CancelDisbursementUseCase;
import com.fintech.disbursement.application.port.in.FindDisbursementUseCase;
import com.fintech.disbursement.application.port.in.RequestDisbursementUseCase;
import com.fintech.disbursement.domain.DisbursementOrder;
import com.fintech.disbursement.domain.DisbursementSource;
import com.fintech.disbursement.domain.Rail;
import com.fintech.disbursement.infrastructure.adapter.in.api.dto.CancelDisbursementRequest;
import com.fintech.disbursement.infrastructure.adapter.in.api.dto.DisbursementEventResponse;
import com.fintech.disbursement.infrastructure.adapter.in.api.dto.DisbursementResponse;
import com.fintech.disbursement.infrastructure.adapter.in.api.dto.RequestDisbursementRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * La puerta de entrada que no es Kafka (DC-6).
 *
 * <p>Un comprador que no tenga nuestra infraestructura de eventos puede operar el servicio sólo con
 * esto. Que exista es la diferencia entre "un módulo de nuestra plataforma" y "un producto".
 */
@RestController
@Validated
@RequestMapping("/api/v1/disbursements")
@Tag(name = "Disbursements", description = "Alta y consulta de pagos salientes")
public class DisbursementController {

    private static final String SOURCE_SYSTEM_API = "api";

    private final RequestDisbursementUseCase requestDisbursement;
    private final FindDisbursementUseCase findDisbursement;
    private final CancelDisbursementUseCase cancelDisbursement;

    public DisbursementController(RequestDisbursementUseCase requestDisbursement,
                                  FindDisbursementUseCase findDisbursement,
                                  CancelDisbursementUseCase cancelDisbursement) {
        this.requestDisbursement = requestDisbursement;
        this.findDisbursement = findDisbursement;
        this.cancelDisbursement = cancelDisbursement;
    }

    /**
     * Devuelve {@code 202 Accepted}, no {@code 201}: la orden queda registrada, el pago aún no
     * ocurrió. Prometer un {@code 201 Created} sobre dinero que todavía no salió sería mentir con
     * un código de estado.
     */
    @PostMapping
    @Operation(summary = "Registra un pago saliente",
            description = "Idempotente por la cabecera Idempotency-Key: repetirla devuelve la misma orden.")
    public ResponseEntity<DisbursementResponse> request(
            @RequestHeader(value = "Idempotency-Key", required = false)
            @Size(max = 120, message = "Idempotency-Key no puede exceder 120 caracteres")
            String idempotencyKey,
            @Valid @RequestBody RequestDisbursementRequest request) {

        String eventId = idempotencyKey != null && !idempotencyKey.isBlank()
                ? idempotencyKey
                : UUID.randomUUID().toString();

        DisbursementOrder order = requestDisbursement.request(new RequestDisbursementCommand(
                SOURCE_SYSTEM_API,
                DisbursementSource.API,
                request.sourceReference(),
                eventId,
                null,
                request.companyId(),
                request.sourceMetadata() != null ? request.sourceMetadata() : Map.of(),
                request.beneficiaryName(),
                request.beneficiaryAccount(),
                request.beneficiaryAccountType(),
                request.beneficiaryTaxId(),
                request.beneficiaryInstitution(),
                request.amount(),
                request.currency(),
                request.concept(),
                request.numericReference(),
                parseRail(request.rail()),
                request.correlationId()));

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/disbursements/" + order.getDisbursementId()))
                .body(DisbursementResponse.from(order));
    }

    private static Rail parseRail(String rail) {
        if (rail == null || rail.isBlank()) {
            return Rail.SPEI;
        }
        try {
            return Rail.valueOf(rail.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Rail desconocido: '" + rail + "'. Válidos: "
                    + java.util.Arrays.toString(Rail.values()));
        }
    }

    @GetMapping("/{disbursementId}")
    @Operation(summary = "Consulta una orden")
    public DisbursementResponse findById(@PathVariable UUID disbursementId) {
        return DisbursementResponse.from(findDisbursement.findById(disbursementId));
    }

    @GetMapping("/{disbursementId}/events")
    @Operation(summary = "Bitácora de transiciones de la orden")
    public List<DisbursementEventResponse> timeline(@PathVariable UUID disbursementId) {
        return findDisbursement.timeline(disbursementId).stream()
                .map(DisbursementEventResponse::from)
                .toList();
    }

    @GetMapping
    @Operation(summary = "Órdenes de una empresa")
    public List<DisbursementResponse> findByCompany(
            @RequestParam UUID companyId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        return findDisbursement.findByCompany(companyId, page, size).stream()
                .map(DisbursementResponse::from)
                .toList();
    }

    @PostMapping("/{disbursementId}/cancel")
    @Operation(summary = "Cancela una orden aún no despachada")
    public DisbursementResponse cancel(@PathVariable UUID disbursementId,
                                       @Valid @RequestBody CancelDisbursementRequest request,
                                       @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return DisbursementResponse.from(
                cancelDisbursement.cancel(disbursementId, request.reason(),
                        userId != null ? userId : "UNKNOWN"));
    }
}
