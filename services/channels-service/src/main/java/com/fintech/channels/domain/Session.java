package com.fintech.channels.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "channels", name = "sessions")
public class Session {

    @Id
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(name = "channel_type", nullable = false)
    private String channelType;

    @Column(name = "party_id")
    private UUID partyId;

    @Embedded
    private DeviceContext device;

    @Column(nullable = false)
    private String status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected Session() {}

    public static Session start(UUID channelId, String channelType, UUID partyId,
                                DeviceContext device, int ttlMinutes) {
        var s = new Session();
        s.sessionId   = UUID.randomUUID();
        s.channelId   = channelId;
        s.channelType = channelType;
        s.partyId     = partyId;
        s.device      = device;
        s.status      = SessionStatus.ACTIVE.name();
        s.startedAt   = Instant.now();
        s.expiresAt   = s.startedAt.plusSeconds(ttlMinutes * 60L);
        return s;
    }

    public void expire() {
        this.status    = SessionStatus.EXPIRED.name();
        this.closedAt  = Instant.now();
    }

    public void close() {
        this.status    = SessionStatus.CLOSED.name();
        this.closedAt  = Instant.now();
    }

    public boolean isAcceptingIntents() {
        return SessionStatus.ACTIVE.name().equals(status) || SessionStatus.IDLE.name().equals(status);
    }

    public UUID getSessionId()    { return sessionId; }
    public UUID getChannelId()    { return channelId; }
    public String getChannelType(){ return channelType; }
    public UUID getPartyId()      { return partyId; }
    public DeviceContext getDevice() { return device; }
    public String getStatus()     { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getClosedAt()  { return closedAt; }
}
