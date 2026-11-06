package com.fintech.salesorg.infrastructure.adapter.in.api;

import com.fintech.salesorg.application.port.in.PlacementPolicyUseCase;
import com.fintech.salesorg.infrastructure.adapter.in.api.dto.PlacementPolicyRequest;
import com.fintech.salesorg.infrastructure.adapter.in.api.dto.PlacementPolicyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The placement policy of the distributor line, per unit.
 *
 * <p>Declared at a branch and inherited by everything below, like the VAT rate; a distributor may declare her own,
 * only stricter. What a direct credit may lend is the score's business, not this one's.
 */
@RestController
@RequestMapping("/api/v1/sales-org/units/by-code/{code}/placement-policy")
@Tag(name = "Sales Org — Placement policy", description = "Límites de colocación de la línea de distribuidora por unidad y tipo de cliente")
class PlacementPolicyController {

    private static final Logger log = LoggerFactory.getLogger(PlacementPolicyController.class);

    private final PlacementPolicyUseCase policies;

    PlacementPolicyController(PlacementPolicyUseCase policies) {
        this.policies = policies;
    }

    @GetMapping
    @Operation(summary = "La política de la unidad: lo que declara y lo que le aplica por herencia")
    PlacementPolicyResponse get(@PathVariable String code) {
        return PlacementPolicyResponse.from(policies.effective(code));
    }

    @GetMapping("/effective")
    @Operation(summary = "Sólo lo que aplica en la unidad, ya resuelta la herencia")
    PlacementPolicyResponse effective(@PathVariable String code) {
        return PlacementPolicyResponse.from(policies.effective(code));
    }

    @PutMapping
    @Operation(summary = "Declara la política propia de la unidad (su subárbol la hereda)",
            description = "Se valida contra la línea de distribuidora del catálogo. En un nodo DISTRIBUIDOR sólo puede "
                        + "ser más estricta que lo heredado. Un cuerpo vacío la retira.")
    PlacementPolicyResponse declare(@PathVariable String code,
                                    @RequestBody(required = false) PlacementPolicyRequest request,
                                    @RequestHeader(value = "X-User-Id", required = false) String staffUserId) {
        log.info("PUT /sales-org/units/by-code/{}/placement-policy by={}", code, staffUserId);
        return PlacementPolicyResponse.from(policies.declare(code,
                request == null ? null : request.toDomain(), staffUserId));
    }

    @DeleteMapping
    @Operation(summary = "Retira la política propia: la unidad vuelve a heredar")
    PlacementPolicyResponse clear(@PathVariable String code,
                                  @RequestHeader(value = "X-User-Id", required = false) String staffUserId) {
        log.info("DELETE /sales-org/units/by-code/{}/placement-policy by={}", code, staffUserId);
        return PlacementPolicyResponse.from(policies.clear(code, staffUserId));
    }
}
