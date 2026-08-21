package com.fintech.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "identity", name = "mfa_config")
public class MfaConfig {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "party_id", nullable = false, updatable = false, unique = true)
    private UUID partyId;

    @Column(name = "totp_secret", nullable = false, updatable = false)
    private String totpSecret;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "enrolled_at")
    private Instant enrolledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MfaConfig() {}

    public static MfaConfig create(UUID partyId, String totpSecret) {
        var m = new MfaConfig();
        m.id = UUID.randomUUID();
        m.partyId = partyId;
        m.totpSecret = totpSecret;
        m.enabled = false;
        m.createdAt = Instant.now();
        m.updatedAt = Instant.now();
        return m;
    }

    public void activate() {
        this.enabled = true;
        this.enrolledAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void disable() {
        this.enabled = false;
        this.updatedAt = Instant.now();
    }

    public UUID getId()          { return id; }
    public UUID getPartyId()     { return partyId; }
    public String getTotpSecret(){ return totpSecret; }
    public boolean isEnabled()   { return enabled; }
    public Instant getEnrolledAt(){ return enrolledAt; }
    public Instant getCreatedAt(){ return createdAt; }
    public Instant getUpdatedAt(){ return updatedAt; }
}
