package com.fintech.party.infrastructure.adapter.in.api.dto;

import com.fintech.party.domain.PartyRelationshipType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Se lee como una frase: el party de la ruta <b>tiene como</b> {@code relationshipType} a
 * {@code relatedPartyId}. Para el alta de un cliente final por su distribuidora, la ruta lleva la
 * distribuidora y {@code relatedPartyId} el beneficiario.
 */
public record CreateRelationshipRequest(

        @NotNull(message = "relatedPartyId es obligatorio")
        UUID relatedPartyId,

        @NotNull(message = "relationshipType es obligatorio")
        PartyRelationshipType relationshipType,

        /** Producto de crédito al que se ata la relación, si aplica. Opcional. */
        UUID creditProductId
) {}
