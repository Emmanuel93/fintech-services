package com.fintech.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "identity", name = "client_ip_whitelist")
public class ClientIpEntry {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    @Column(nullable = false, length = 50)
    private String cidr;

    @Column(length = 100)
    private String label;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ClientIpEntry() {}

    public static ClientIpEntry create(UUID clientId, String cidr, String label) {
        var e = new ClientIpEntry();
        e.id = UUID.randomUUID();
        e.clientId = clientId;
        e.cidr = cidr;
        e.label = label;
        e.createdAt = Instant.now();
        return e;
    }

    public UUID getId()       { return id; }
    public UUID getClientId() { return clientId; }
    public String getCidr()   { return cidr; }
    public String getLabel()  { return label; }
    public Instant getCreatedAt() { return createdAt; }
}
