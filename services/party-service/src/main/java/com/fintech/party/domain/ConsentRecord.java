package com.fintech.party.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consent_records", schema = "party")
public class ConsentRecord {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID consentId;

    @Column(nullable = false, updatable = false)
    private UUID partyId;

    @Column(nullable = false, updatable = false, length = 50)
    private String consentType;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, updatable = false)
    private Instant grantedAt;

    private Instant expiresAt;

    private Instant revokedAt;

    @Column(length = 300)
    private String revocationReason;

    @Column(length = 500)
    private String documentRef;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ConsentRecord() {}

    public static ConsentRecord grant(UUID partyId, String consentType, Instant expiresAt, String documentRef) {
        ConsentRecord c = new ConsentRecord();
        c.consentId        = UUID.randomUUID();
        c.partyId          = partyId;
        c.consentType      = consentType;
        c.status           = "GRANTED";
        c.grantedAt        = Instant.now();
        c.expiresAt        = expiresAt;
        c.documentRef      = documentRef;
        c.createdAt        = c.grantedAt;
        return c;
    }

    public void revoke(String reason) {
        this.status            = "REVOKED";
        this.revokedAt         = Instant.now();
        this.revocationReason  = reason;
    }

    public boolean isActive() { return "GRANTED".equals(status); }

    public UUID getConsentId()         { return consentId; }
    public UUID getPartyId()           { return partyId; }
    public String getConsentType()     { return consentType; }
    public String getStatus()          { return status; }
    public Instant getGrantedAt()      { return grantedAt; }
    public Instant getExpiresAt()      { return expiresAt; }
    public Instant getRevokedAt()      { return revokedAt; }
    public String getRevocationReason(){ return revocationReason; }
    public String getDocumentRef()     { return documentRef; }
    public Instant getCreatedAt()      { return createdAt; }
}
