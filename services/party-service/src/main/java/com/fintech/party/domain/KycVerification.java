package com.fintech.party.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "kyc_verifications", schema = "party")
public class KycVerification {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID verificationId;

    @Column(nullable = false, updatable = false)
    private UUID partyId;

    @Column(nullable = false, updatable = false, length = 30)
    private String documentType;

    @Column(nullable = false, length = 30)
    private String verificationStatus;

    @Column(length = 100)
    private String verifiedBy;

    private Instant verifiedAt;

    @Column(length = 300)
    private String rejectionReason;

    @Column(length = 500)
    private String documentRef;

    private LocalDate expiresAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected KycVerification() {}

    public static KycVerification create(UUID partyId, String documentType) {
        KycVerification v = new KycVerification();
        v.verificationId     = UUID.randomUUID();
        v.partyId            = partyId;
        v.documentType       = documentType;
        v.verificationStatus = "PENDING";
        v.createdAt          = Instant.now();
        return v;
    }

    public void verify(String verifiedBy, String documentRef, LocalDate expiresAt) {
        this.verificationStatus = "VERIFIED";
        this.verifiedBy         = verifiedBy;
        this.verifiedAt         = Instant.now();
        this.documentRef        = documentRef;
        this.expiresAt          = expiresAt;
        this.rejectionReason    = null;
    }

    public void reject(String rejectionReason) {
        this.verificationStatus = "REJECTED";
        this.rejectionReason    = rejectionReason;
        this.verifiedAt         = Instant.now();
    }

    public boolean isVerified() { return "VERIFIED".equals(verificationStatus); }

    public UUID getVerificationId()    { return verificationId; }
    public UUID getPartyId()           { return partyId; }
    public String getDocumentType()    { return documentType; }
    public String getVerificationStatus() { return verificationStatus; }
    public String getVerifiedBy()      { return verifiedBy; }
    public Instant getVerifiedAt()     { return verifiedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public String getDocumentRef()     { return documentRef; }
    public LocalDate getExpiresAt()    { return expiresAt; }
    public Instant getCreatedAt()      { return createdAt; }
}
