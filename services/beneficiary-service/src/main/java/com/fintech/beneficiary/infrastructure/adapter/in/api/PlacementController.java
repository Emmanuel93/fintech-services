package com.fintech.beneficiary.infrastructure.adapter.in.api;

import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.application.service.PlacementOrchestrator;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementAccessDeniedException;
import com.fintech.beneficiary.domain.PlacementStatus;
import com.fintech.beneficiary.infrastructure.adapter.in.api.dto.BureauReportResponse;
import com.fintech.beneficiary.infrastructure.adapter.in.api.dto.PlacementListResponse;
import com.fintech.beneficiary.infrastructure.adapter.in.api.dto.PlacementResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Las colocaciones de un distribuidor.
 *
 * <p>El {@code distributorPartyId} sale del {@code X-User-Id} que inyecta el gateway y nunca de un
 * parámetro: pedirlo en la ruta dejaría que cualquiera con una sesión válida leyera la cartera de
 * otro distribuidor.
 *
 * <p>Fase 1 expone sólo lectura. El {@code POST} llega con la fase 2, porque crear una colocación
 * es inseparable de acuñar su liga: una colocación sin invitación no tiene forma de avanzar.
 */
@RestController
@RequestMapping("/api/v1/placements")
@Tag(name = "Colocaciones", description = "Ciclo de vida de la colocación B2B2C")
class PlacementController {

    private final PlacementLifecycleUseCase placementLifecycle;
    private final PlacementRepository placementRepository;
    private final PlacementOrchestrator orchestrator;
    private final BureauReportAssembler bureauReports;

    PlacementController(PlacementLifecycleUseCase placementLifecycle,
                        PlacementRepository placementRepository,
                        PlacementOrchestrator orchestrator,
                        BureauReportAssembler bureauReports) {
        this.placementLifecycle  = placementLifecycle;
        this.placementRepository = placementRepository;
        this.orchestrator        = orchestrator;
        this.bureauReports       = bureauReports;
    }

    @Operation(summary = "Mis colocaciones",
               description = "Las del distribuidor autenticado, más recientes primero. "
                           + "Filtra por estado con ?status=.")
    @ApiResponse(responseCode = "200", description = "Lista de colocaciones")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    ResponseEntity<PlacementListResponse> list(@RequestParam(required = false) PlacementStatus status) {
        UUID distributorPartyId = currentPartyId();
        List<Placement> placements = status == null
                ? placementRepository.findByDistributorPartyIdOrderByCreatedAtDesc(distributorPartyId)
                : placementRepository.findByDistributorPartyIdAndStatusOrderByCreatedAtDesc(distributorPartyId, status);
        return ResponseEntity.ok(new PlacementListResponse(
                placements.stream()
                        .map(p -> PlacementResponse.from(p, orchestrator.metricsOf(p)))
                        .toList()));
    }

    @Operation(summary = "Enviar un préstamo",
               description = "Crea la colocación y deja lista su liga. **No descuenta la línea**: "
                           + "eso ocurre al aprobar, después de que la beneficiaria se verifique.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Colocación creada, liga enviada"),
            @ApiResponse(responseCode = "409", description = "Ya hay una liga viva para ese celular, "
                                                           + "o el monto supera la línea disponible"),
            @ApiResponse(responseCode = "422", description = "Monto o plazo fuera de lo que permite el producto")
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    ResponseEntity<PlacementResponse> create(@Valid @RequestBody CreatePlacementRequest req) {
        Placement placement = orchestrator.create(
                currentPartyId(),
                req.beneficiary().fullName(),
                req.beneficiary().phone(),
                req.beneficiary().relationship(),
                req.amount(),
                req.termFortnights());
        return ResponseEntity.status(HttpStatus.CREATED).body(PlacementResponse.from(placement));
    }

    @Operation(summary = "Reenviar la liga",
               description = "Revoca la anterior y reinicia los 7 días. No los extiende: si "
                           + "extendiera, la vigencia sería infinita a punta de reenvíos.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{placementId}/resend")
    ResponseEntity<PlacementResponse> resend(@PathVariable UUID placementId) {
        Placement placement = orchestrator.resendInvite(placementId, currentPartyId());
        return ResponseEntity.ok(PlacementResponse.from(placement));
    }

    @Operation(summary = "Revocar la colocación",
               description = "Sólo mientras no haya expediente. Con documentos entregados y "
                           + "autorización firmada, la salida es rechazar — una decisión con nombre.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{placementId}/cancel")
    ResponseEntity<PlacementResponse> cancel(@PathVariable UUID placementId,
                                             @RequestBody(required = false) ReasonRequest req) {
        Placement placement = placementLifecycle.cancel(placementId, currentPartyId(),
                req == null || req.reason() == null ? "Revocada por el distribuidor" : req.reason());
        return ResponseEntity.ok(PlacementResponse.from(placement));
    }

    @Operation(summary = "Su historial de buró",
               description = "El reporte completo, sin filtrar por score: Kredius no aprueba ni "
                           + "rechaza, decide el distribuidor. Se obtiene con la autorización que "
                           + "firmó ella, no la de él.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "El reporte"),
            @ApiResponse(responseCode = "409", description = "Su verificación sigue en curso")
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{placementId}/bureau")
    ResponseEntity<BureauReportResponse> bureau(@PathVariable UUID placementId) {
        Placement placement = ownedPlacement(placementId);
        return ResponseEntity.ok(bureauReports.assemble(placement));
    }

    @Operation(summary = "Aprobar y armar su crédito",
               description = "Descuenta la línea y dispara el depósito. Exige la aceptación "
                           + "explícita del riesgo: es la constancia de que vio el historial.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Aprobada; el depósito va en camino"),
            @ApiResponse(responseCode = "409", description = "No está esperando decisión, o la línea no alcanza"),
            @ApiResponse(responseCode = "422", description = "Sin aceptación de riesgo")
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{placementId}/approve")
    ResponseEntity<PlacementResponse> approve(@PathVariable UUID placementId,
                                              @RequestBody(required = false) ApproveRequest req) {
        Placement placement = orchestrator.approve(placementId, currentPartyId(),
                req != null && req.riskAcknowledged());
        return ResponseEntity.ok(PlacementResponse.from(placement));
    }

    @Operation(summary = "No colocar por ahora")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{placementId}/reject")
    ResponseEntity<PlacementResponse> reject(@PathVariable UUID placementId,
                                             @RequestBody(required = false) ReasonRequest req) {
        Placement placement = orchestrator.reject(placementId, currentPartyId(),
                req == null ? null : req.reason());
        return ResponseEntity.ok(PlacementResponse.from(placement));
    }

    private Placement ownedPlacement(UUID placementId) {
        UUID distributorPartyId = currentPartyId();
        Placement placement = placementLifecycle.findById(placementId);
        if (!placement.getDistributorPartyId().equals(distributorPartyId)) {
            throw new PlacementAccessDeniedException(placementId, distributorPartyId);
        }
        return placement;
    }

    /** Los tres campos de la pantalla 18 más el monto y plazo de la 22. */
    record CreatePlacementRequest(
            @NotNull @Valid BeneficiaryDraft beneficiary,
            @NotNull @DecimalMin("1.00") BigDecimal amount,
            @Min(1) int termFortnights,
            String verificationMode) {

        record BeneficiaryDraft(
                @NotBlank String fullName,
                @NotBlank @Pattern(regexp = "\\d{10}", message = "El celular debe tener 10 dígitos") String phone,
                String relationship) {}
    }

    record ApproveRequest(boolean riskAcknowledged) {}

    record ReasonRequest(String reason) {}

    @Operation(summary = "Una colocación")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "La colocación"),
            @ApiResponse(responseCode = "403", description = "No es del distribuidor autenticado"),
            @ApiResponse(responseCode = "404", description = "No existe")
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{placementId}")
    ResponseEntity<PlacementResponse> getById(@PathVariable UUID placementId) {
        UUID distributorPartyId = currentPartyId();
        Placement placement = placementLifecycle.findById(placementId);
        if (!placement.getDistributorPartyId().equals(distributorPartyId)) {
            throw new PlacementAccessDeniedException(placementId, distributorPartyId);
        }
        return ResponseEntity.ok(PlacementResponse.from(placement));
    }

    private static UUID currentPartyId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(auth.getName());
    }
}
