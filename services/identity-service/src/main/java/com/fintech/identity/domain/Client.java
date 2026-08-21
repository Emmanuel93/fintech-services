package com.fintech.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(schema = "identity", name = "clients")
public class Client {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "client_id", nullable = false, updatable = false, unique = true, length = 100)
    private String clientId;

    @Column(name = "secret_hash", nullable = false)
    private String secretHash;

    @Column(name = "client_name", nullable = false, length = 200)
    private String clientName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClientStatus status;

    // Comma-separated roles, e.g. "SYSTEM_SCORING,SYSTEM_RISK"
    @Column(nullable = false, columnDefinition = "text")
    private String roles;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Client() {}

    public static Client create(String clientId, String clientName,
                                String secretHash, List<String> roles,
                                Instant expiresAt) {
        var c = new Client();
        c.id = UUID.randomUUID();
        c.clientId = clientId;
        c.clientName = clientName;
        c.secretHash = secretHash;
        c.status = ClientStatus.ACTIVE;
        c.roles = roles == null || roles.isEmpty() ? "" : String.join(",", roles);
        c.expiresAt = expiresAt;
        c.createdAt = Instant.now();
        c.updatedAt = Instant.now();
        return c;
    }

    public boolean isActive() {
        return status == ClientStatus.ACTIVE
                && (expiresAt == null || expiresAt.isAfter(Instant.now()));
    }

    public void disable() {
        this.status = ClientStatus.DISABLED;
        this.updatedAt = Instant.now();
    }

    public UUID getId()           { return id; }
    public String getClientId()   { return clientId; }
    public String getSecretHash() { return secretHash; }
    public String getClientName() { return clientName; }
    public ClientStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public List<String> getRoles() {
        return roles == null || roles.isBlank() ? List.of() : List.of(roles.split(","));
    }
}
