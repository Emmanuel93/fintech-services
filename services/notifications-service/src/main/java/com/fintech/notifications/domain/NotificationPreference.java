package com.fintech.notifications.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Capturado por el cliente vía la app (PUT /preferences) — no viene de ningún evento de dominio. */
@Entity
@Table(name = "notification_preferences", schema = "notifications")
public class NotificationPreference {

    @Id
    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    @Column(name = "push_token")
    private String pushToken;

    /** Si es null, se usa el teléfono resuelto en PartyContactDirectory para WhatsApp también. */
    @Column(name = "whatsapp_number")
    private String whatsappNumber;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationPreference() {}

    public static NotificationPreference create(UUID partyId, String pushToken, String whatsappNumber) {
        NotificationPreference p = new NotificationPreference();
        p.partyId        = partyId;
        p.pushToken       = pushToken;
        p.whatsappNumber  = whatsappNumber;
        p.updatedAt       = Instant.now();
        return p;
    }

    public void update(String pushToken, String whatsappNumber) {
        this.pushToken      = pushToken;
        this.whatsappNumber = whatsappNumber;
        this.updatedAt      = Instant.now();
    }

    public UUID getPartyId()          { return partyId; }
    public String getPushToken()      { return pushToken; }
    public String getWhatsappNumber() { return whatsappNumber; }
    public Instant getUpdatedAt()     { return updatedAt; }
}
