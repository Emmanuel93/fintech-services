package com.fintech.party.infrastructure.adapter.out.persistence;

import com.fintech.party.application.port.out.PartyRelationshipRepository;
import com.fintech.party.domain.PartyRelationship;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaPartyRelationshipAdapter implements PartyRelationshipRepository {

    private final SpringDataPartyRelationshipRepository repository;

    public JpaPartyRelationshipAdapter(SpringDataPartyRelationshipRepository repository) {
        this.repository = repository;
    }

    @Override
    public PartyRelationship save(PartyRelationship relationship) {
        return repository.save(relationship);
    }

    @Override
    public Optional<PartyRelationship> findById(UUID relationshipId) {
        return repository.findById(relationshipId);
    }

    @Override
    public List<PartyRelationship> findActiveByPartyId(UUID partyId) {
        return repository.findAllByPartyIdAndActiveTrue(partyId);
    }

    @Override
    public List<PartyRelationship> findActiveByRelatedPartyId(UUID relatedPartyId) {
        return repository.findAllByRelatedPartyIdAndActiveTrue(relatedPartyId);
    }

    @Override
    public Optional<PartyRelationship> findActive(UUID partyId, UUID relatedPartyId, String relationshipType) {
        return repository.findByPartyIdAndRelatedPartyIdAndRelationshipTypeAndActiveTrue(
                partyId, relatedPartyId, relationshipType);
    }
}
