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

    /** Cómo se intentó resolver. Lo trae quien conoce el producto; aquí sólo se guarda. */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_mode", length = 20)
    private VerificationMode verificationMode;

    /** Quién emitió el veredicto, tal como fue entonces. No se deduce del modo vigente. */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_source", length = 20)
    private VerificationSource verificationSource;

    /** Cuál proveedor. `PROVIDER` sin decir cuál deja de ser una respuesta con más de uno. */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 30)
    private KycProvider provider;

    /** El `interviewId` o `sessionId` del proveedor: lo que permite reabrir el caso. */
    @Column(name = "provider_reference", length = 120)
    private String providerReference;

    /** Lo que el proveedor devolvió y no cabe en columnas. JSONB para no acoplarse a todos. */
    @Column(name = "provider_metadata", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private java.util.Map<String, Object> providerMetadata;

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

    /**
     * La verificó una persona.
     *
     * <p>Deja {@code provider = NONE} a propósito, y no nulo: «lo resolvió una persona» es un
     * veredicto legítimo y completo, no un dato que falte.
     */
    public void verify(String verifiedBy, String documentRef, LocalDate expiresAt) {
        verifyManually(verifiedBy, documentRef, expiresAt, VerificationMode.MANUAL);
    }

    /**
     * La revisó una persona, con el modo que el producto exigía.
     *
     * <p>El modo importa aunque el veredicto sea humano: no es lo mismo un producto que pide
     * revisión manual desde el principio que uno que la pide porque el proveedor no alcanzó umbral.
     * Lo segundo es {@link VerificationSource#PROVIDER_ESCALATED} y se registra con
     * {@link #verifyWithProvider}.
     */
    public void verifyManually(String verifiedBy, String documentRef, LocalDate expiresAt,
                                     VerificationMode modo) {
        this.verificationStatus  = "VERIFIED";
        this.verifiedBy          = verifiedBy;
        this.verifiedAt          = Instant.now();
        this.documentRef         = documentRef;
        this.expiresAt           = expiresAt;
        this.rejectionReason     = null;
        this.verificationMode    = modo;
        this.verificationSource  = VerificationSource.MANUAL;
        this.provider            = KycProvider.NONE;
    }

    /**
     * La resolvió un proveedor, con su evidencia.
     *
     * <p>Exige {@code provider} y {@code providerReference}: un veredicto de proveedor sin decir
     * cuál ni con qué sesión no se puede reabrir ni defender, y la restricción de la tabla lo
     * rechaza igualmente.
     *
     * @param source {@code PROVIDER} si resolvió solo; {@code PROVIDER_ESCALATED} si una persona
     *               tuvo que resolver encima.
     */
    public void verifyWithProvider(KycProvider provider, String providerReference,
                                      java.util.Map<String, Object> metadata,
                                      VerificationSource source, String verifiedBy,
                                      String documentRef, LocalDate expiresAt) {
        if (provider == null || provider == KycProvider.NONE) {
            throw new IllegalArgumentException(
                    "Un veredicto de proveedor tiene que decir CUÁL proveedor");
        }
        if (providerReference == null || providerReference.isBlank()) {
            throw new IllegalArgumentException(
                    "Sin referencia del proveedor la verificación no se puede reabrir: "
                            + "es el identificador de la sesión, no un adorno");
        }
        this.verificationStatus  = "VERIFIED";
        this.verifiedBy          = verifiedBy;
        this.verifiedAt          = Instant.now();
        this.documentRef         = documentRef;
        this.expiresAt           = expiresAt;
        this.rejectionReason     = null;
        this.verificationMode    = VerificationMode.AUTOMATIC;
        this.verificationSource  = source;
        this.provider            = provider;
        this.providerReference   = providerReference;
        this.providerMetadata    = metadata;
    }

    public void reject(String rejectionReason) {
        this.verificationStatus = "REJECTED";
        this.rejectionReason    = rejectionReason;
        this.verifiedAt         = Instant.now();
    }

    public boolean isVerified() { return "VERIFIED".equals(verificationStatus); }

    public VerificationMode getVerificationMode()     { return verificationMode; }
    public VerificationSource getVerificationSource() { return verificationSource; }
    public KycProvider getProvider()                  { return provider; }
    public String getProviderReference()              { return providerReference; }
    public java.util.Map<String, Object> getProviderMetadata() { return providerMetadata; }

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
