package com.fintech.party.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "party_relationships", schema = "party")
public class PartyRelationship {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID relationshipId;

    @Column(nullable = false, updatable = false)
    private UUID partyId;

    @Column(nullable = false, updatable = false)
    private UUID relatedPartyId;

    @Column(nullable = false, updatable = false, length = 50)
    private String relationshipType;

    @Column(updatable = false)
    private UUID creditProductId;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant endedAt;

    protected PartyRelationship() {}

    public static PartyRelationship create(UUID partyId, UUID relatedPartyId,
                                           String relationshipType, UUID creditProductId) {
        PartyRelationship r = new PartyRelationship();
        r.relationshipId   = UUID.randomUUID();
        r.partyId          = partyId;
        r.relatedPartyId   = relatedPartyId;
        r.relationshipType = relationshipType;
        r.creditProductId  = creditProductId;
        r.active           = true;
        r.createdAt        = Instant.now();
        return r;
    }

    public void end() {
        this.active  = false;
        this.endedAt = Instant.now();
    }

    public UUID getRelationshipId()  { return relationshipId; }
    public UUID getPartyId()         { return partyId; }
    public UUID getRelatedPartyId()  { return relatedPartyId; }
    public String getRelationshipType() { return relationshipType; }
    public UUID getCreditProductId() { return creditProductId; }
    public boolean isActive()        { return active; }
    public Instant getCreatedAt()    { return createdAt; }
    public Instant getEndedAt()      { return endedAt; }
}
