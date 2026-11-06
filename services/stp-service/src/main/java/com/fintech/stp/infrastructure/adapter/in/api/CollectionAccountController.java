package com.fintech.stp.infrastructure.adapter.in.api;

import com.fintech.stp.application.port.in.ManageCollectionAccountUseCase;
import com.fintech.stp.infrastructure.adapter.in.api.dto.CollectionAccountResponse;
import com.fintech.stp.infrastructure.adapter.in.api.dto.InboundCreditResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** A person's collection CLABE and the credits received on it, for the back office. */
@RestController
@RequestMapping("/api/v1/stp/collection-accounts")
@Tag(name = "STP Collection", description = "CLABEs de cobro por persona")
class CollectionAccountController {

    private final ManageCollectionAccountUseCase collection;

    CollectionAccountController(ManageCollectionAccountUseCase collection) {
        this.collection = collection;
    }

    @GetMapping("/by-party/{partyId}")
    @Operation(summary = "CLABE de cobro de una persona")
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<CollectionAccountResponse> byParty(@PathVariable UUID partyId) {
        return collection.findByParty(partyId).map(CollectionAccountResponse::from)
                .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/by-party/{partyId}/credits")
    @Operation(summary = "Abonos recibidos en la CLABE de cobro de una persona, con sus dos fechas")
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<List<InboundCreditResponse>> credits(@PathVariable UUID partyId) {
        return ResponseEntity.ok(collection.creditsOf(partyId).stream().map(InboundCreditResponse::from).toList());
    }

    @PostMapping("/by-party/{partyId}/block")
    @Operation(summary = "Bloquea la CLABE: los abonos se devuelven con causa 2")
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<CollectionAccountResponse> block(@PathVariable UUID partyId, @RequestParam String reason) {
        return ResponseEntity.ok(CollectionAccountResponse.from(collection.block(partyId, reason)));
    }

    @PostMapping("/by-party/{partyId}/reactivate")
    @Operation(summary = "Reactiva una CLABE bloqueada")
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<CollectionAccountResponse> reactivate(@PathVariable UUID partyId, @RequestParam String reason) {
        return ResponseEntity.ok(CollectionAccountResponse.from(collection.reactivate(partyId, reason)));
    }
}
