package com.fintech.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(schema = "identity", name = "credentials")
public class IdentityCredential {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_type", nullable = false, updatable = false)
    private CredentialType credentialType;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CredentialStatus status;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** IPv4 (hasta 15 chars) o IPv6 (hasta 45 chars). */
    @Column(name = "last_login_ip", length = 45)
    private String lastLoginIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IdentityCredential() {}

    public static IdentityCredential create(UUID partyId, String username, CredentialType type, String passwordHash) {
        var c = new IdentityCredential();
        c.id = UUID.randomUUID();
        c.partyId = partyId;
        c.username = username;
        c.credentialType = type;
        c.passwordHash = passwordHash;
        c.status = CredentialStatus.ACTIVE;
        c.failedAttempts = 0;
        c.createdAt = Instant.now();
        c.updatedAt = Instant.now();
        return c;
    }

    // ── Business methods ─────────────────────────────────────────────────

    public boolean isLocked() {
        return status == CredentialStatus.LOCKED
                && lockedUntil != null
                && lockedUntil.isAfter(Instant.now());
    }

    public void recordFailure(int maxAttempts, int lockDurationMinutes) {
        failedAttempts++;
        if (failedAttempts >= maxAttempts) {
            status = CredentialStatus.LOCKED;
            lockedUntil = Instant.now().plus(lockDurationMinutes, ChronoUnit.MINUTES);
        }
        updatedAt = Instant.now();
    }

    public void resetFailures() {
        failedAttempts = 0;
        status = CredentialStatus.ACTIVE;
        lockedUntil = null;
        updatedAt = Instant.now();
    }

    public void recordLogin(Instant loginAt, String ipAddress) {
        this.lastLoginAt = loginAt;
        this.lastLoginIp = ipAddress;
        this.updatedAt = Instant.now();
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getPartyId() { return partyId; }
    public String getUsername() { return username; }
    public CredentialType getCredentialType() { return credentialType; }
    public String getPasswordHash() { return passwordHash; }
    public CredentialStatus getStatus() { return status; }
    public int getFailedAttempts() { return failedAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public String getLastLoginIp() { return lastLoginIp; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
