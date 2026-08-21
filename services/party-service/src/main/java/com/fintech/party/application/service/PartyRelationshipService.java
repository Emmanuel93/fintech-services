package com.fintech.party.application.service;

import com.fintech.party.application.port.out.PartyRelationshipRepository;
import com.fintech.party.application.port.out.PartyRepository;
import com.fintech.party.domain.PartyNotFoundException;
import com.fintech.party.domain.PartyRelationship;
import com.fintech.party.domain.PartyRelationshipType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * El vínculo entre dos parties. La tabla, la entidad y el repositorio existían desde la migración
 * 006 y no había forma de crear ni consultar una relación: el dato estaba modelado y era
 * inalcanzable.
 *
 * <p>Es lo que sostiene el B2B2C. Cuando una distribuidora da de alta a su cliente final, el
 * expediente lo captura ella y la institución responde por ese tratamiento de datos; sin un vínculo
 * registrado no hay a quién preguntarle por un documento mal tomado, y «los beneficiarios de esta
 * distribuidora» no es una consulta que se pueda hacer.
 */
@Service
@Transactional
public class PartyRelationshipService {

    private static final Logger log = LoggerFactory.getLogger(PartyRelationshipService.class);

    private final PartyRelationshipRepository relationshipRepository;
    private final PartyRepository partyRepository;

    public PartyRelationshipService(PartyRelationshipRepository relationshipRepository,
                                    PartyRepository partyRepository) {
        this.relationshipRepository = relationshipRepository;
        this.partyRepository        = partyRepository;
    }

    /**
     * Crea el vínculo. Idempotente: si ya está vigente el mismo, devuelve el existente en vez de
     * duplicarlo — dar de alta dos veces al mismo beneficiario es un reintento, no dos relaciones.
     */
    public PartyRelationship relate(UUID partyId, UUID relatedPartyId,
                                    PartyRelationshipType type, UUID creditProductId) {

        if (partyId.equals(relatedPartyId)) {
            throw new IllegalArgumentException("Un party no se puede relacionar consigo mismo");
        }
        // Se comprueban los dos extremos. Un vínculo contra un party inexistente pasaría la
        // restricción de llave foránea sólo por casualidad, y el error saldría como un 500 de base
        // en vez de decir cuál de los dos no existe.
        if (partyRepository.findById(partyId).isEmpty())        throw new PartyNotFoundException(partyId);
        if (partyRepository.findById(relatedPartyId).isEmpty()) throw new PartyNotFoundException(relatedPartyId);

        return relationshipRepository.findActive(partyId, relatedPartyId, type.name())
                .orElseGet(() -> {
                    PartyRelationship saved = relationshipRepository.save(
                            PartyRelationship.create(partyId, relatedPartyId, type.name(), creditProductId));
                    log.info("Relación creada partyId={} {} relatedPartyId={} relationshipId={}",
                            partyId, type, relatedPartyId, saved.getRelationshipId());
                    return saved;
                });
    }

    /** Cierra el vínculo. No lo borra: queda con su fecha de fin. */
    public boolean end(UUID relationshipId) {
        return relationshipRepository.findById(relationshipId)
                .filter(PartyRelationship::isActive)
                .map(r -> {
                    r.end();
                    relationshipRepository.save(r);
                    log.info("Relación cerrada relationshipId={}", relationshipId);
                    return true;
                })
                .orElse(false);
    }

    /** Lo que sale del party: a quién avala, a quién le coloca crédito. */
    @Transactional(readOnly = true)
    public List<PartyRelationship> outgoing(UUID partyId) {
        return relationshipRepository.findActiveByPartyId(partyId);
    }

    /** Lo que llega al party: quién lo avala, qué distribuidora le colocó. */
    @Transactional(readOnly = true)
    public List<PartyRelationship> incoming(UUID partyId) {
        return relationshipRepository.findActiveByRelatedPartyId(partyId);
    }
}
