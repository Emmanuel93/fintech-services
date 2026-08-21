package com.fintech.party.application.port.out;

import com.fintech.party.domain.PartyRelationship;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartyRelationshipRepository {
    PartyRelationship save(PartyRelationship relationship);

    Optional<PartyRelationship> findById(UUID relationshipId);

    /** Las relaciones vigentes que <b>salen</b> del party: a quién avala, a quién le coloca. */
    List<PartyRelationship> findActiveByPartyId(UUID partyId);

    /**
     * Las relaciones vigentes que <b>llegan</b> al party: quién lo avala, quién le colocó.
     *
     * <p>Hace falta para responder «¿de qué distribuidora es este beneficiario?», que es la
     * pregunta que se hace desde la ficha del cliente. Sin ella sólo se podía recorrer el vínculo
     * en un sentido, y la respuesta exigía barrer todas las distribuidoras.
     */
    List<PartyRelationship> findActiveByRelatedPartyId(UUID relatedPartyId);

    /** La relación vigente exacta, para no duplicarla al re-otorgarla. */
    Optional<PartyRelationship> findActive(UUID partyId, UUID relatedPartyId, String relationshipType);
}
