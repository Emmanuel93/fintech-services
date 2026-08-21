package com.fintech.channelmobile.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintech.channelmobile.infrastructure.adapter.out.client.BeneficiaryClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Colocación B2B2C — la puerta de la app hacia beneficiary-service.
 *
 * <p>Los paths son los que la app ya consume (`docs/KREDIUS_COLOCACION_API.md`), sin el prefijo
 * `/api/v1` que usan los servicios de dominio: para la app, esto es el BFF y no hay nada detrás.
 *
 * <p>El {@code X-User-Id} lo inyecta el gateway tras validar el JWT y aquí sólo se reenvía. Nunca
 * se acepta un distributorId por parámetro: sería dejar que cualquiera con sesión válida leyera la
 * cartera de otro.
 */
@RestController
@Tag(name = "Colocación", description = "Enviar préstamos a beneficiarios (B2B2C)")
public class PlacementController {

    private final BeneficiaryClient beneficiary;
    private final boolean kycSimulationEnabled;

    public PlacementController(BeneficiaryClient beneficiary,
                               @Value("${fintech.channel-mobile.kyc-simulation-enabled:false}")
                               boolean kycSimulationEnabled) {
        this.beneficiary = beneficiary;
        this.kycSimulationEnabled = kycSimulationEnabled;
    }

    @Operation(summary = "Mi línea de distribuidor")
    @GetMapping("/distributor/line-summary")
    public ResponseEntity<JsonNode> lineSummary(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(beneficiary.lineSummary(userId));
    }

    @Operation(summary = "Mis colocaciones")
    @GetMapping("/placements")
    public ResponseEntity<JsonNode> list(@RequestHeader("X-User-Id") String userId,
                                         @RequestParam(required = false) String status) {
        return ResponseEntity.ok(beneficiary.listPlacements(userId, status));
    }

    @Operation(summary = "Enviar un préstamo")
    @PostMapping("/placements")
    public ResponseEntity<JsonNode> create(@RequestHeader("X-User-Id") String userId,
                                           @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(beneficiary.createPlacement(userId, body));
    }

    @Operation(summary = "Una colocación")
    @GetMapping("/placements/{placementId}")
    public ResponseEntity<JsonNode> get(@RequestHeader("X-User-Id") String userId,
                                        @PathVariable UUID placementId) {
        return ResponseEntity.ok(beneficiary.getPlacement(userId, placementId));
    }

    @Operation(summary = "Su historial de buró")
    @GetMapping("/placements/{placementId}/bureau")
    public ResponseEntity<JsonNode> bureau(@RequestHeader("X-User-Id") String userId,
                                           @PathVariable UUID placementId) {
        return ResponseEntity.ok(beneficiary.bureau(userId, placementId));
    }

    @Operation(summary = "Aprobar y armar su crédito")
    @PostMapping("/placements/{placementId}/approve")
    public ResponseEntity<JsonNode> approve(@RequestHeader("X-User-Id") String userId,
                                            @PathVariable UUID placementId,
                                            @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(beneficiary.approve(userId, placementId,
                body == null ? Map.of() : body));
    }

    @Operation(summary = "No colocar por ahora")
    @PostMapping("/placements/{placementId}/reject")
    public ResponseEntity<JsonNode> reject(@RequestHeader("X-User-Id") String userId,
                                           @PathVariable UUID placementId,
                                           @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(beneficiary.reject(userId, placementId,
                body == null ? Map.of() : body));
    }

    @Operation(summary = "Reenviar la liga")
    @PostMapping("/placements/{placementId}/resend")
    public ResponseEntity<JsonNode> resend(@RequestHeader("X-User-Id") String userId,
                                           @PathVariable UUID placementId) {
        return ResponseEntity.ok(beneficiary.resend(userId, placementId));
    }

    @Operation(summary = "Revocar la colocación")
    @PostMapping("/placements/{placementId}/cancel")
    public ResponseEntity<JsonNode> cancel(@RequestHeader("X-User-Id") String userId,
                                           @PathVariable UUID placementId,
                                           @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(beneficiary.cancel(userId, placementId,
                body == null ? Map.of() : body));
    }

    @Operation(summary = "Mis beneficiarios")
    @GetMapping("/beneficiaries")
    public ResponseEntity<JsonNode> beneficiaries(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(beneficiary.beneficiaries(userId));
    }

    /**
     * Cierra el KYC de la beneficiaria desde el servidor.
     *
     * <p>Vive bajo {@code /internal/test-support/} —igual que los jobs de devengo y mora— porque
     * es exactamente eso: la palanca que permite probar el flujo completo sin la web pública de
     * la beneficiaria, que todavía no se construye. Apagada por configuración fuera de local.
     */
    @Operation(summary = "[local] Completar el KYC de la beneficiaria")
    @PostMapping("/internal/test-support/placements/{placementId}/complete-kyc")
    public ResponseEntity<JsonNode> completeKyc(@PathVariable UUID placementId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        if (!kycSimulationEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "La simulación de KYC está apagada en este ambiente");
        }
        return ResponseEntity.ok(beneficiary.simulateKyc(placementId, body));
    }
}
