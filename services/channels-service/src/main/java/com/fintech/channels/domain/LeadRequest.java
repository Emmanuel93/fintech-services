package com.fintech.channels.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "channels", name = "lead_requests")
public class LeadRequest {

    @Id
    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(name = "intent_type", nullable = false)
    private String intentType;

    @Column(nullable = false)
    private String status;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name1")
    private String lastName1;

    @Column
    private String phone;

    @Column
    private String email;

    @Column(name = "promoter_party_id")
    private UUID promoterPartyId;

    @Column(name = "converted_party_id")
    private UUID convertedPartyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LeadRequest() {}

    public static LeadRequest create(UUID channelId, IntentType intentType,
                                     String firstName, String lastName1,
                                     String phone, String email,
                                     UUID promoterPartyId, int ttlDays) {
        var lr = new LeadRequest();
        lr.leadId = UUID.randomUUID();
        lr.channelId = channelId;
        lr.intentType = intentType.name();
        lr.status = LeadStatus.NEW.name();
        lr.firstName = firstName;
        lr.lastName1 = lastName1;
        lr.phone = phone;
        lr.email = email;
        lr.promoterPartyId = promoterPartyId;
        lr.createdAt = Instant.now();
        lr.updatedAt = lr.createdAt;
        lr.expiresAt = lr.createdAt.plusSeconds(ttlDays * 86400L);
        return lr;
    }

    public void convert(UUID convertedPartyId) {
        if (LeadStatus.CONVERTED.name().equals(status)) {
            throw new IllegalStateException("Lead already converted — immutable (L-02)");
        }
        this.convertedPartyId = convertedPartyId;
        this.status = LeadStatus.CONVERTED.name();
        this.updatedAt = Instant.now();
    }

    public boolean isConverted() { return LeadStatus.CONVERTED.name().equals(status); }

    public UUID getLeadId() { return leadId; }
    public UUID getChannelId() { return channelId; }
    public String getIntentType() { return intentType; }
    public String getStatus() { return status; }
    public String getFirstName() { return firstName; }
    public String getLastName1() { return lastName1; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public UUID getPromoterPartyId() { return promoterPartyId; }
    public UUID getConvertedPartyId() { return convertedPartyId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
