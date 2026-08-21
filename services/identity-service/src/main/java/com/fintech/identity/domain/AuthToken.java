package com.fintech.identity.domain;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthToken {

    private UUID id;
    private String tokenId;
    private UUID partyId;
    private String deviceId;
    private String refreshTokenHash;
    private Instant issuedAt;
    private Instant expiresAt;
    private boolean revoked;
    private Instant revokedAt;

    /** IP del cliente que generó esta sesión; útil para análisis forense. */
    private String ipAddress;

    /** User-Agent del cliente que generó esta sesión. */
    private String userAgent;

    /**
     * Canal que emitió la sesión. Sin él, refrescar un token de backoffice por el endpoint móvil lo
     * degradaría silenciosamente a una sesión de cliente (y al revés). Nulo en sesiones creadas
     * antes de existir el campo: se leen como MOBILE, el único canal que había entonces.
     */
    private Channel channel;

    protected AuthToken() {}

    public static AuthToken create(UUID partyId, String deviceId,
                                   String refreshTokenHash, String jti,
                                   int expiryDays, String ipAddress, String userAgent,
                                   Channel channel) {
        var t = new AuthToken();
        t.id = UUID.randomUUID();
        t.tokenId = jti;
        t.partyId = partyId;
        t.deviceId = deviceId;
        t.refreshTokenHash = refreshTokenHash;
        t.issuedAt = Instant.now();
        t.expiresAt = Instant.now().plus(expiryDays, ChronoUnit.DAYS);
        t.revoked = false;
        t.ipAddress = ipAddress;
        t.userAgent = userAgent;
        t.channel = channel;
        return t;
    }

    public void revoke() {
        this.revoked = true;
        this.revokedAt = Instant.now();
    }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    public UUID getId()                  { return id; }
    public String getTokenId()           { return tokenId; }
    public UUID getPartyId()             { return partyId; }
    public String getDeviceId()          { return deviceId; }
    public String getRefreshTokenHash()  { return refreshTokenHash; }
    public Instant getIssuedAt()         { return issuedAt; }
    public Instant getExpiresAt()        { return expiresAt; }
    public boolean isRevoked()           { return revoked; }
    public Instant getRevokedAt()        { return revokedAt; }
    public String getIpAddress()         { return ipAddress; }
    public String getUserAgent()         { return userAgent; }
    public Channel getChannel()          { return channel != null ? channel : Channel.MOBILE; }

    /** Falla si la sesión no pertenece al canal del flujo que la está usando. */
    public void requireChannel(Channel expected) {
        if (getChannel() != expected) {
            throw new ChannelMismatchException(expected, getChannel());
        }
    }
}
