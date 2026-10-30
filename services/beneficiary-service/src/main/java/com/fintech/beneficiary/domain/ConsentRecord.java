package com.fintech.beneficiary.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * La constancia de un consentimiento: qué aceptó, qué versión del texto, cuándo, desde dónde.
 *
 * <p>Es append-only y por eso no tiene un solo setter. Una autorización de buró que se pudiera
 * editar después no probaría nada: su valor está en que quedó escrita en el instante en que ella
 * tocó «Acepto», con la IP y el navegador desde los que lo hizo.
 *
 * <p>{@code sessionId} es la referencia de verificación OTP: la sesión sólo existe detrás de un
 * código mandado a su celular, así que ata el consentimiento a un teléfono demostrablemente suyo.
 * Es el {@code otpVerificationId} que viaja en {@code beneficiary.bureau-consent-granted}.
 */
@Entity
@Table(schema = "beneficiary", name = "consent_records")
public class ConsentRecord {

    @Id
    @Column(name = "consent_id", nullable = false, updatable = false)
    private UUID consentId;

    @Column(name = "placement_id", nullable = false, updatable = false)
    private UUID placementId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, updatable = false, length = 30)
    private ConsentType consentType;

    @Column(name = "text_version", nullable = false, updatable = false, length = 40)
    private String textVersion;

    @Column(name = "accepted_at", nullable = false, updatable = false)
    private Instant acceptedAt;

    @Column(name = "ip_address", updatable = false, length = 45)
    private String ipAddress;

    @Column(name = "user_agent", updatable = false, length = 500)
    private String userAgent;

    protected ConsentRecord() {}

    public static ConsentRecord accept(UUID placementId, UUID sessionId, ConsentType type,
                                       String textVersion, String ipAddress, String userAgent,
                                       Instant now) {
        if (textVersion == null || textVersion.isBlank()) {
            throw new PlacementValidationException(
                    "Un consentimiento sin versión del texto no prueba a qué se dijo que sí");
        }
        ConsentRecord c = new ConsentRecord();
        c.consentId   = UUID.randomUUID();
        c.placementId = placementId;
        c.sessionId   = sessionId;
        c.consentType = type;
        c.textVersion = textVersion.trim();
        c.acceptedAt  = now;
        c.ipAddress   = ipAddress == null ? null
                : ipAddress.length() <= 45 ? ipAddress : ipAddress.substring(0, 45);
        c.userAgent   = userAgent == null ? null
                : userAgent.length() <= 500 ? userAgent : userAgent.substring(0, 500);
        return c;
    }

    public UUID getConsentId()          { return consentId; }
    public UUID getPlacementId()        { return placementId; }
    public UUID getSessionId()          { return sessionId; }
    public ConsentType getConsentType() { return consentType; }
    public String getTextVersion()      { return textVersion; }
    public Instant getAcceptedAt()      { return acceptedAt; }
    public String getIpAddress()        { return ipAddress; }
    public String getUserAgent()        { return userAgent; }
}
