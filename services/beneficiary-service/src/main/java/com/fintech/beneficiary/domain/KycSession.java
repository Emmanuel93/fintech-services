package com.fintech.beneficiary.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * La sesión de invitado: lo único que la beneficiaria llega a tener como credencial (§3.2).
 *
 * <p>Nace detrás de un OTP verificado, dura {@code guestSessionTtlMinutes} (15) <b>deslizantes</b>
 * —cada paso que da la renueva— y nunca vive más que su liga. Es opaca: 32 bytes aleatorios de
 * los que sólo se guarda el hash, igual que el token de la liga.
 *
 * <p>Guarda también el avance que no es un archivo: los datos que capturó (JSON) y el prospecto
 * ya creado en origination. Esto último es lo que hace reintentable el cierre: origination
 * rechaza una CURP duplicada, así que un segundo intento que olvidara el primer prospecto quedaría
 * atorado para siempre.
 */
@Entity
@Table(schema = "beneficiary", name = "kyc_sessions")
public class KycSession {

    @Id
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "placement_id", nullable = false, updatable = false)
    private UUID placementId;

    @Column(name = "invite_id", nullable = false, updatable = false)
    private UUID inviteId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "session_token_hash", nullable = false, updatable = false, length = 64)
    private String sessionTokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * El hash del OTP con el que se abrió esta sesión. Al enviar, ella vuelve a teclear ese mismo
     * código: es su firma, y tiene que ser la del teléfono que verificó, no un código cualquiera.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "verified_otp_hash", length = 64)
    private String verifiedOtpHash;

    /** Lo que capturó en «Tus datos», en JSON. Nulo hasta que lo guarda. */
    @Column(name = "captured_data")
    private String capturedData;

    /** El prospecto creado en origination. Se escribe antes de seguir, para que un reintento lo reuse. */
    @Column(name = "prospect_id")
    private UUID prospectId;

    @Column(name = "ip", length = 45)
    private String ip;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KycSession() {}

    public static KycSession open(UUID placementId, UUID inviteId, String sessionTokenHash,
                                  String verifiedOtpHash, Instant expiresAt,
                                  String ip, String userAgent, Instant now) {
        KycSession s = new KycSession();
        s.sessionId        = UUID.randomUUID();
        s.placementId      = placementId;
        s.inviteId         = inviteId;
        s.sessionTokenHash = sessionTokenHash;
        s.verifiedOtpHash  = verifiedOtpHash;
        s.expiresAt        = expiresAt;
        s.ip               = truncate(ip, 45);
        s.userAgent        = truncate(userAgent, 500);
        s.createdAt        = now;
        s.updatedAt        = now;
        return s;
    }

    /**
     * «Si te interrumpen, retomas donde quedaste» (paso 1). La sesión nueva hereda lo capturado y
     * el prospecto de la anterior; los archivos y consentimientos no hace falta heredarlos porque
     * cuelgan de la colocación, no de la sesión.
     */
    public void resumeFrom(KycSession previous) {
        if (previous == null) return;
        if (capturedData == null) capturedData = previous.capturedData;
        if (prospectId == null)   prospectId   = previous.prospectId;
    }

    /** Usable para avanzar: abierta y sin caducar. */
    public boolean isUsable(Instant now) {
        return closedAt == null && expiresAt.isAfter(now);
    }

    /** Vigente aunque ya esté cerrada: alcanza para leer que terminó. */
    public boolean isAlive(Instant now) {
        return expiresAt.isAfter(now);
    }

    public boolean isClosed() {
        return closedAt != null;
    }

    /** Cada paso renueva los 15 minutos, sin pasar del vencimiento de la liga. */
    public void slide(Instant newExpiry, Instant now) {
        if (closedAt != null) return;
        this.expiresAt = newExpiry;
        this.updatedAt = now;
    }

    public void recordData(String json, Instant now) {
        this.capturedData = json;
        this.updatedAt    = now;
    }

    public void attachProspect(UUID prospectId) {
        if (this.prospectId == null) this.prospectId = prospectId;
    }

    public void close(Instant now) {
        if (closedAt == null) closedAt = now;
        this.updatedAt = now;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public UUID getSessionId()          { return sessionId; }
    public UUID getPlacementId()        { return placementId; }
    public UUID getInviteId()           { return inviteId; }
    public String getSessionTokenHash() { return sessionTokenHash; }
    public Instant getExpiresAt()       { return expiresAt; }
    public Instant getClosedAt()        { return closedAt; }
    public String getVerifiedOtpHash()  { return verifiedOtpHash; }
    public String getCapturedData()     { return capturedData; }
    public UUID getProspectId()         { return prospectId; }
    public String getIp()               { return ip; }
    public String getUserAgent()        { return userAgent; }
    public Instant getCreatedAt()       { return createdAt; }
    public Instant getUpdatedAt()       { return updatedAt; }
}
