package com.fintech.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Dispositivo registrado para un party.
 * Se crea la primera vez que un party autentica desde un deviceId nuevo
 * y se actualiza en cada autenticación posterior.
 * Permite detectar accesos desde dispositivos desconocidos y soportar
 * políticas de confianza por dispositivo (PCI-DSS, CNBV).
 */
@Entity
@Table(schema = "identity", name = "devices",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_device_party_client_id",
                columnNames = {"party_id", "client_device_id"}))
public class Device {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    /** ID opaco enviado por el cliente (UUID generado en instalación de la app, etc.). */
    @Column(name = "client_device_id", nullable = false, updatable = false, length = 255)
    private String clientDeviceId;

    /** Plataforma derivada del User-Agent: IOS, ANDROID, WEB, DESKTOP, API, UNKNOWN. */
    @Column(name = "platform", length = 20)
    private String platform;

    /** Sistema operativo (ej. "iOS 17.4", "Android 14"). */
    @Column(name = "os", length = 100)
    private String os;

    /** Navegador o app (ej. "Chrome 124", "fintech-app/3.2"). */
    @Column(name = "browser", length = 100)
    private String browser;

    /** Modelo de hardware (ej. "iPhone 15 Pro", "Samsung Galaxy S23"). */
    @Column(name = "model", length = 100)
    private String model;

    /** User-Agent completo — fuente de verdad para reanálisis futuro. */
    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "first_seen_ip", updatable = false, length = 45)
    private String firstSeenIp;

    @Column(name = "last_seen_ip", length = 45)
    private String lastSeenIp;

    /** true si el party ha marcado explícitamente este dispositivo como de confianza. */
    @Column(nullable = false)
    private boolean trusted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeviceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Device() {}

    public static Device create(UUID partyId, String clientDeviceId,
                                String platform, String os, String browser,
                                String model, String userAgent,
                                String ipAddress) {
        var d = new Device();
        d.id = UUID.randomUUID();
        d.partyId = partyId;
        d.clientDeviceId = clientDeviceId;
        d.platform = platform;
        d.os = os;
        d.browser = browser;
        d.model = model;
        d.userAgent = userAgent;
        d.firstSeenAt = Instant.now();
        d.lastSeenAt = d.firstSeenAt;
        d.firstSeenIp = ipAddress;
        d.lastSeenIp = ipAddress;
        d.trusted = false;
        d.status = DeviceStatus.ACTIVE;
        d.createdAt = d.firstSeenAt;
        return d;
    }

    /** Actualiza los campos mutables en cada autenticación posterior. */
    public void recordSeen(String ipAddress, String userAgent) {
        this.lastSeenAt = Instant.now();
        this.lastSeenIp = ipAddress;
        this.userAgent = userAgent;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public UUID getId()             { return id; }
    public UUID getPartyId()        { return partyId; }
    public String getClientDeviceId() { return clientDeviceId; }
    public String getPlatform()     { return platform; }
    public String getOs()           { return os; }
    public String getBrowser()      { return browser; }
    public String getModel()        { return model; }
    public String getUserAgent()    { return userAgent; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastSeenAt()  { return lastSeenAt; }
    public String getFirstSeenIp()  { return firstSeenIp; }
    public String getLastSeenIp()   { return lastSeenIp; }
    public boolean isTrusted()      { return trusted; }
    public DeviceStatus getStatus() { return status; }
    public Instant getCreatedAt()   { return createdAt; }
}
