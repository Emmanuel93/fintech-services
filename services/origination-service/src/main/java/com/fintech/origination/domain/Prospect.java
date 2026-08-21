package com.fintech.origination.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root for prospect intake.
 *
 * Invariants:
 *  - privacyNoticeAccepted must be true before creation (LFPDPPP Art. 9)
 *  - CURP is immutable and must be unique per prospect record
 *  - Status is terminal after CONVERTED or EXPIRED
 */
@Entity
@Table(name = "prospects", schema = "origination",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_prospects_curp",  columnNames = "curp"),
                @UniqueConstraint(name = "uq_prospects_phone", columnNames = "phone")
        })
public class Prospect {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID prospectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProspectType prospectType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProspectStatus status;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName1;

    private String lastName2;

    @Column(nullable = false, unique = true, length = 18)
    private String curp;

    @Column(length = 13)
    private String rfc;

    @Column(nullable = false)
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gender gender;

    @Column(nullable = false)
    private String stateOfBirth;

    @Column(nullable = false, unique = true, length = 20)
    private String phone;

    private String email;

    @Embedded
    private ProspectAddress address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChannelType channelType;

    @Column(nullable = false)
    private boolean privacyNoticeAccepted;

    @Column(nullable = false)
    private Instant privacyNoticeAcceptedAt;

    @Column(nullable = false)
    private boolean circuloConsentAccepted;

    @Column(nullable = false)
    private Instant circuloConsentAcceptedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "prospect_documents",
            schema = "origination",
            joinColumns = @JoinColumn(name = "prospect_id")
    )
    private List<ProspectDocument> documents = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    protected Prospect() {}

    public static Prospect create(
            UUID prospectId,
            ProspectType prospectType,
            String firstName, String lastName1, String lastName2,
            String curp, String rfc,
            LocalDate dateOfBirth, Gender gender, String stateOfBirth,
            String phone, String email,
            ProspectAddress address,
            ChannelType channelType,
            boolean privacyNoticeAccepted,
            boolean circuloConsentAccepted,
            List<ProspectDocument> documents,
            int expiryDays) {

        if (!privacyNoticeAccepted) {
            throw new PrivacyNoticeRequiredException();
        }

        Instant now = Instant.now();
        Prospect p = new Prospect();
        p.prospectId              = prospectId;
        p.prospectType            = prospectType;
        p.status                  = ProspectStatus.CAPTURED;
        p.firstName               = firstName;
        p.lastName1               = lastName1;
        p.lastName2               = lastName2;
        p.curp                    = curp.toUpperCase();
        p.rfc                     = (rfc != null) ? rfc.toUpperCase() : null;
        p.dateOfBirth             = dateOfBirth;
        p.gender                  = gender;
        p.stateOfBirth            = stateOfBirth;
        p.phone                   = phone;
        p.email                   = email;
        p.address                 = address;
        p.channelType             = channelType;
        p.privacyNoticeAccepted    = true;
        p.privacyNoticeAcceptedAt  = now;
        p.circuloConsentAccepted   = circuloConsentAccepted;
        p.circuloConsentAcceptedAt = circuloConsentAccepted ? now : null;
        if (documents != null) {
            p.documents.addAll(documents);
        }
        p.createdAt               = now;
        p.expiresAt               = now.plusSeconds((long) expiryDays * 86_400);
        return p;
    }

    public void markSubmitted() {
        if (status != ProspectStatus.CAPTURED) {
            throw new IllegalStateException("Cannot submit prospect in status " + status);
        }
        this.status = ProspectStatus.SUBMITTED;
    }

    public void markConverted() {
        if (status == ProspectStatus.CONVERTED || status == ProspectStatus.EXPIRED) {
            throw new IllegalStateException("Cannot convert prospect in terminal status " + status);
        }
        this.status = ProspectStatus.CONVERTED;
    }

    public void expire() {
        if (status == ProspectStatus.CONVERTED) return;
        this.status = ProspectStatus.EXPIRED;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public UUID getProspectId()                  { return prospectId; }
    public ProspectType getProspectType()        { return prospectType; }
    public ProspectStatus getStatus()            { return status; }
    public String getFirstName()                 { return firstName; }
    public String getLastName1()                 { return lastName1; }
    public String getLastName2()                 { return lastName2; }
    public String getCurp()                      { return curp; }
    public String getRfc()                       { return rfc; }
    public LocalDate getDateOfBirth()            { return dateOfBirth; }
    public Gender getGender()                    { return gender; }
    public String getStateOfBirth()              { return stateOfBirth; }
    public String getPhone()                     { return phone; }
    public String getEmail()                     { return email; }
    public ProspectAddress getAddress()          { return address; }
    public ChannelType getChannelType()          { return channelType; }
    public boolean isPrivacyNoticeAccepted()     { return privacyNoticeAccepted; }
    public Instant getPrivacyNoticeAcceptedAt()  { return privacyNoticeAcceptedAt; }
    public boolean isCirculoConsentAccepted()     { return circuloConsentAccepted; }
    public Instant getCirculoConsentAcceptedAt()  { return circuloConsentAcceptedAt; }
    public List<ProspectDocument> getDocuments() { return Collections.unmodifiableList(documents); }
    public Instant getCreatedAt()                { return createdAt; }
    public Instant getExpiresAt()                { return expiresAt; }
}
