package com.fintech.commission.infrastructure.adapter.in.api;

import com.fintech.commission.application.CreateCommissionPolicyCommand;
import com.fintech.commission.application.port.in.CommissionPolicyUseCase;
import com.fintech.commission.application.port.in.GetCommissionUseCase;
import com.fintech.commission.application.service.LiquidationService;
import com.fintech.commission.application.service.PromoterAssignmentService;
import com.fintech.commission.infrastructure.adapter.in.api.dto.CommissionPolicyResponse;
import com.fintech.commission.infrastructure.adapter.in.api.dto.CommissionRecordResponse;
import com.fintech.commission.infrastructure.adapter.in.api.dto.CreateCommissionPolicyRequest;
import com.fintech.commission.infrastructure.adapter.in.api.dto.PromoterCreditResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/commissions")
@Tag(name = "Commission", description = "Acumulación y liquidación de comisiones — distribuidores B2B2C, promotores, gestores de cobranza")
@SecurityRequirement(name = "bearerAuth")
class CommissionController {

    private final GetCommissionUseCase getCommissionUseCase;
    private final CommissionPolicyUseCase policyUseCase;
    private final LiquidationService liquidationService;
    private final PromoterAssignmentService promoterAssignmentService;

    CommissionController(GetCommissionUseCase getCommissionUseCase, CommissionPolicyUseCase policyUseCase,
                          LiquidationService liquidationService,
                          PromoterAssignmentService promoterAssignmentService) {
        this.getCommissionUseCase = getCommissionUseCase;
        this.policyUseCase        = policyUseCase;
        this.liquidationService   = liquidationService;
        this.promoterAssignmentService = promoterAssignmentService;
    }

    @Operation(summary = "Historial de comisiones de un crédito")
    @GetMapping("/accounts/{creditAccountId}")
    ResponseEntity<List<CommissionRecordResponse>> byAccount(@PathVariable UUID creditAccountId) {
        return ResponseEntity.ok(getCommissionUseCase.getByCreditAccountId(creditAccountId)
                .stream().map(CommissionRecordResponse::from).toList());
    }

    @Operation(summary = "Comisiones ACCRUED (sin liquidar) de un beneficiario")
    @GetMapping("/beneficiaries/{partyId}/pending")
    ResponseEntity<List<CommissionRecordResponse>> pendingByBeneficiary(@PathVariable UUID partyId) {
        return ResponseEntity.ok(getCommissionUseCase.getPendingByBeneficiary(partyId)
                .stream().map(CommissionRecordResponse::from).toList());
    }

    @Operation(summary = "Créditos colocados por un conjunto de distribuidores (acota cartera por alcance)")
    @GetMapping("/promoters/credits")
    ResponseEntity<List<PromoterCreditResponse>> creditsByPromoters(@RequestParam List<UUID> partyIds) {
        return ResponseEntity.ok(promoterAssignmentService.creditsByPromoters(partyIds)
                .stream().map(PromoterCreditResponse::from).toList());
    }

    @Operation(summary = "Tasas de comisión vigentes (transparencia)")
    @GetMapping("/policies")
    ResponseEntity<List<CommissionPolicyResponse>> listPolicies() {
        return ResponseEntity.ok(policyUseCase.listActive()
                .stream().map(CommissionPolicyResponse::from).toList());
    }

    @Operation(summary = "Crea/versiona una tasa de comisión (equipo comercial)")
    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    CommissionPolicyResponse createPolicy(@Valid @RequestBody CreateCommissionPolicyRequest req) {
        return CommissionPolicyResponse.from(policyUseCase.create(new CreateCommissionPolicyCommand(
                req.productType(), req.distributorPartyId(), req.commissionType(), req.rate())));
    }

    @Operation(summary = "Dispara la corrida de liquidación de un período (ops/finanzas)")
    @PostMapping("/liquidation-runs")
    ResponseEntity<Map<String, Object>> runLiquidation(@RequestParam String period) {
        int batches = liquidationService.runLiquidation(period);
        return ResponseEntity.ok(Map.of("period", period, "batchesCreated", batches));
    }
}
