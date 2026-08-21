package com.fintech.notifications.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Una ACTIVE por eventType (mismo patrón que CommissionPolicy/ProvisionPolicy). Define el canal
 * primario y la cadena de fallback recomendada — Notifications decide el canal a partir de esto,
 * nunca lo hardcodea evento-por-evento (ver T2_notifications.md §Estrategia de canal).
 */
@Entity
@Table(name = "notification_policies", schema = "notifications")
public class NotificationPolicy {

    @Id
    @Column(name = "policy_id", nullable = false, updatable = false)
    private UUID policyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false)
    private EventType eventType;

    /**
     * La llave real de la política. Texto libre: un emisor nuevo registra la suya sin que este
     * servicio tenga que recompilarse para conocerla.
     */
    @Column(name = "event_key", nullable = false, length = 80)
    private String eventKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_tier", nullable = false, updatable = false)
    private ValueTier valueTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_strategy", nullable = false, updatable = false)
    private ChannelStrategy channelStrategy;

    @Enumerated(EnumType.STRING)
    @Column(name = "primary_channel", nullable = false, updatable = false)
    private NotificationChannel primaryChannel;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "notification_policy_fallback_channels", schema = "notifications",
            joinColumns = @JoinColumn(name = "policy_id"))
    @Column(name = "channel", nullable = false)
    @Enumerated(EnumType.STRING)
    @OrderColumn(name = "position")
    private List<NotificationChannel> fallbackChannels = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PolicyStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationPolicy() {}

    public static NotificationPolicy create(EventType eventType, ValueTier valueTier,
                                              ChannelStrategy channelStrategy, NotificationChannel primaryChannel,
                                              List<NotificationChannel> fallbackChannels, int version) {
        NotificationPolicy p = new NotificationPolicy();
        p.policyId          = UUID.randomUUID();
        p.eventType          = eventType;
        p.eventKey  = eventType == null ? null : eventType.name();
        p.valueTier           = valueTier;
        p.channelStrategy     = channelStrategy;
        p.primaryChannel      = primaryChannel;
        p.fallbackChannels    = fallbackChannels == null ? new ArrayList<>() : new ArrayList<>(fallbackChannels);
        p.version             = version;
        p.status              = PolicyStatus.ACTIVE;
        p.createdAt           = Instant.now();
        return p;
    }

    /** Orden de intento: primario primero, luego los fallback en orden. */
    public List<NotificationChannel> orderedChannels() {
        List<NotificationChannel> ordered = new ArrayList<>();
        ordered.add(primaryChannel);
        ordered.addAll(fallbackChannels);
        return ordered;
    }

    public void deprecate() { this.status = PolicyStatus.DEPRECATED; }

    public boolean isActive() { return status == PolicyStatus.ACTIVE; }

    public UUID getPolicyId()                       { return policyId; }
    public EventType getEventType()                 { return eventType; }
    public String getEventKey()                     { return eventKey; }
    public ValueTier getValueTier()                 { return valueTier; }
    public ChannelStrategy getChannelStrategy()      { return channelStrategy; }
    public NotificationChannel getPrimaryChannel()   { return primaryChannel; }
    public List<NotificationChannel> getFallbackChannels() { return fallbackChannels; }
    public int getVersion()                          { return version; }
    public PolicyStatus getStatus()                   { return status; }
    public Instant getCreatedAt()                      { return createdAt; }
}
