package com.fintech.party.infrastructure.adapter.out.persistence;

import com.fintech.party.domain.PartyRelationship;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataPartyRelationshipRepository extends JpaRepository<PartyRelationship, UUID> {

    List<PartyRelationship> findAllByPartyIdAndActiveTrue(UUID partyId);

    List<PartyRelationship> findAllByRelatedPartyIdAndActiveTrue(UUID relatedPartyId);

    Optional<PartyRelationship> findByPartyIdAndRelatedPartyIdAndRelationshipTypeAndActiveTrue(
            UUID partyId, UUID relatedPartyId, String relationshipType);
}
