package com.fintech.party.infrastructure.adapter.in.api;

import com.fintech.party.application.service.PartyRelationshipService;
import com.fintech.party.infrastructure.adapter.in.api.dto.CreateRelationshipRequest;
import com.fintech.party.infrastructure.adapter.in.api.dto.PartyRelationshipResponse;
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
 * Vínculos entre parties: aval, representante legal, obligado solidario y —el que sostiene el
 * B2B2C— distribuidora ↔ beneficiario.
 *
 * <p>La dirección importa y se lee desde la ruta: el party de la ruta <b>tiene como</b> X a
 * {@code relatedPartyId}. Por eso hay dos consultas y no una: {@code /relationships} devuelve lo que
 * sale del party (sus beneficiarios) y {@code /relationships/incoming} lo que llega (qué
 * distribuidora lo dio de alta). Con una sola, la segunda pregunta obligaba a barrer todas las
 * distribuidoras.
 */
@RestController
@RequestMapping("/api/v1/parties/{partyId}/relationships")
@Tag(name = "Party Relationships", description = "Vínculos entre parties (aval, distribuidora↔beneficiario)")
class PartyRelationshipController {

    private static final Logger log = LoggerFactory.getLogger(PartyRelationshipController.class);

    private final PartyRelationshipService relationshipService;

    PartyRelationshipController(PartyRelationshipService relationshipService) {
        this.relationshipService = relationshipService;
    }

    @GetMapping
    @Operation(summary = "Relaciones vigentes que salen del party (p. ej. sus beneficiarios)")
    List<PartyRelationshipResponse> outgoing(@PathVariable UUID partyId) {
        return relationshipService.outgoing(partyId).stream().map(PartyRelationshipResponse::from).toList();
    }

    @GetMapping("/incoming")
    @Operation(summary = "Relaciones vigentes que llegan al party (p. ej. qué distribuidora lo dio de alta)")
    List<PartyRelationshipResponse> incoming(@PathVariable UUID partyId) {
        return relationshipService.incoming(partyId).stream().map(PartyRelationshipResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear el vínculo (idempotente si ya está vigente)")
    PartyRelationshipResponse create(@PathVariable UUID partyId,
                                     @Valid @RequestBody CreateRelationshipRequest request) {
        log.info("POST /parties/{}/relationships type={} related={}",
                partyId, request.relationshipType(), request.relatedPartyId());
        return PartyRelationshipResponse.from(relationshipService.relate(
                partyId, request.relatedPartyId(), request.relationshipType(), request.creditProductId()));
    }

    @DeleteMapping("/{relationshipId}")
    @Operation(summary = "Cerrar el vínculo — queda con su fecha de fin, no se borra")
    ResponseEntity<Void> end(@PathVariable UUID partyId, @PathVariable UUID relationshipId) {
        log.info("DELETE /parties/{}/relationships/{}", partyId, relationshipId);
        return relationshipService.end(relationshipId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
