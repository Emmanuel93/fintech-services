package com.fintech.salesorg.infrastructure.adapter.in.api;

import com.fintech.salesorg.application.port.in.OrgUnitUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Resolución de un código de distribuidor a su partyId. Es lo que desactiva la bomba CM-07: al
 * originar, origination resuelve el promoterCode del canal aquí; si no resuelve, rechaza la solicitud
 * en vez de dejar pasar un crédito que nunca acreditaría comisión.
 */
@RestController
@RequestMapping("/api/v1/sales-org/distributors")
@Tag(name = "Sales Org — Distributors", description = "Resolución de código de distribuidor")
class DistributorController {

    private final OrgUnitUseCase orgUnitUseCase;

    DistributorController(OrgUnitUseCase orgUnitUseCase) {
        this.orgUnitUseCase = orgUnitUseCase;
    }

    @GetMapping("/by-code/{code}")
    @Operation(summary = "Resolver el código de un distribuidor a su partyId (promoterCode)")
    ResponseEntity<Map<String, UUID>> byCode(@PathVariable String code) {
        return orgUnitUseCase.resolveDistributorByCode(code)
                .map(partyId -> ResponseEntity.ok(Map.of("partyId", partyId)))
                .orElse(ResponseEntity.notFound().build());
    }
}
