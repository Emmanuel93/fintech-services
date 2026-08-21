package com.fintech.configuration.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "configuration", name = "config_audit_trail")
public class ConfigAuditTrail {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "parameter_id", nullable = false, updatable = false)
    private UUID parameterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ConfigAuditAction action;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    protected ConfigAuditTrail() {}

    public static ConfigAuditTrail record(UUID parameterId, ConfigAuditAction action,
                                           UUID actorId, String oldValue, String newValue) {
        ConfigAuditTrail entry = new ConfigAuditTrail();
        entry.id = UUID.randomUUID();
        entry.parameterId = parameterId;
        entry.action = action;
        entry.actorId = actorId;
        entry.timestamp = Instant.now();
        entry.oldValue = oldValue;
        entry.newValue = newValue;
        return entry;
    }

    public UUID getId()           { return id; }
    public UUID getParameterId()  { return parameterId; }
    public ConfigAuditAction getAction() { return action; }
    public UUID getActorId()      { return actorId; }
    public Instant getTimestamp() { return timestamp; }
    public String getOldValue()   { return oldValue; }
    public String getNewValue()   { return newValue; }
}
