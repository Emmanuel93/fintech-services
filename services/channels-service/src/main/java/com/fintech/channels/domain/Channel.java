package com.fintech.channels.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(schema = "channels", name = "channels")
public class Channel {

    @Id
    @Column(name = "channel_id")
    private UUID channelId;

    @Column(name = "channel_type", nullable = false, unique = true)
    private String channelType;

    @Column(nullable = false)
    private String status;

    @Column(name = "allowed_intents", nullable = false, columnDefinition = "TEXT")
    private String allowedIntents;

    @Column(name = "session_ttl_minutes", nullable = false)
    private int sessionTtlMinutes;

    @Column(name = "max_idle_minutes", nullable = false)
    private int maxIdleMinutes;

    @Column(name = "rate_limit_per_hour", nullable = false)
    private int rateLimitPerHour;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Channel() {}

    public static Channel create(ChannelType type, Set<IntentType> allowedIntents,
                                 int sessionTtlMinutes, int maxIdleMinutes, int rateLimitPerHour) {
        var ch = new Channel();
        ch.channelId = UUID.randomUUID();
        ch.channelType = type.name();
        ch.status = ChannelStatus.ACTIVE.name();
        ch.allowedIntents = allowedIntents.stream().map(Enum::name).collect(Collectors.joining(","));
        ch.sessionTtlMinutes = sessionTtlMinutes;
        ch.maxIdleMinutes = maxIdleMinutes;
        ch.rateLimitPerHour = rateLimitPerHour;
        ch.createdAt = Instant.now();
        ch.updatedAt = ch.createdAt;
        return ch;
    }

    public boolean isActive() { return ChannelStatus.ACTIVE.name().equals(status); }

    public boolean allowsIntent(IntentType intentType) {
        return getAllowedIntentSet().contains(intentType.name());
    }

    public Set<String> getAllowedIntentSet() {
        if (allowedIntents == null || allowedIntents.isBlank()) return Set.of();
        return Arrays.stream(allowedIntents.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    public UUID getChannelId() { return channelId; }
    public String getChannelType() { return channelType; }
    public String getStatus() { return status; }
    public int getSessionTtlMinutes() { return sessionTtlMinutes; }
    public int getMaxIdleMinutes() { return maxIdleMinutes; }
    public int getRateLimitPerHour() { return rateLimitPerHour; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
