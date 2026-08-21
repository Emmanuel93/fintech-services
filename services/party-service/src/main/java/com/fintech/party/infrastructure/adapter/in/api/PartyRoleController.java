package com.fintech.party.infrastructure.adapter.in.api;

import com.fintech.party.application.service.PartyRoleService;
import com.fintech.party.domain.PartyRoleType;
import com.fintech.party.infrastructure.adapter.in.api.dto.GrantRoleRequest;
import com.fintech.party.infrastructure.adapter.in.api.dto.PartyRoleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Roles adicionales de un party (I-03): DISTRIBUTOR, GUARANTOR, BENEFICIARY. No cambian el
 * {@code partyType}; son capacidades aditivas. Administración desde el backoffice.
 */
@RestController
@RequestMapping("/api/v1/parties/{partyId}/roles")
@Tag(name = "Party Roles", description = "Roles comerciales/legales aditivos de un party")
class PartyRoleController {

    private static final Logger log = LoggerFactory.getLogger(PartyRoleController.class);

    private final PartyRoleService roleService;

    PartyRoleController(PartyRoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    @Operation(summary = "Roles vigentes del party")
    List<PartyRoleResponse> list(@PathVariable UUID partyId) {
        return roleService.listActive(partyId).stream().map(PartyRoleResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Otorgar un rol (idempotente si ya está vigente)")
    PartyRoleResponse grant(@PathVariable UUID partyId,
                            @Valid @RequestBody GrantRoleRequest request,
                            @RequestHeader(value = "X-User-Id", required = false) String grantedBy) {
        log.info("POST /parties/{}/roles role={} by={}", partyId, request.roleType(), grantedBy);
        return PartyRoleResponse.from(roleService.grant(partyId, request.roleType(), grantedBy));
    }

    @DeleteMapping("/{roleType}")
    @Operation(summary = "Revocar el rol vigente del party")
    ResponseEntity<Void> revoke(@PathVariable UUID partyId, @PathVariable PartyRoleType roleType) {
        log.info("DELETE /parties/{}/roles/{}", partyId, roleType);
        boolean revoked = roleService.revoke(partyId, roleType);
        return revoked ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
