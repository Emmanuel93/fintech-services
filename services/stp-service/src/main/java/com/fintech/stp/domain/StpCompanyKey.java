package com.fintech.stp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Material criptográfico de una empresa, con envelope encryption.
 *
 * <p>Para {@link KeyPurpose#SIGNING} se guarda la privada cifrada con una DEK, y la DEK cifrada con
 * una KEK que <strong>nunca</strong> está en la base de datos. Un dump de Postgres no compromete
 * ninguna llave. Se guarda además la pública derivada, que sirve para el fingerprint y para que el
 * stub de ambientes bajos pueda verificar lo que firmamos.
 *
 * <p>Para {@link KeyPurpose#VERIFICATION} se guarda la pública de STP en claro — es pública.
 *
 * <p>Inv: KY-01 el material privado nunca sale del servicio: ni por API, ni en logs, ni en trazas.
 * Inv: KY-06 una llave vencida no firma. La orden falla, no se degrada.
 */
@Entity
@Table(schema = "stp", name = "company_keys")
public class StpCompanyKey {

    @Id
    @Column(name = "key_id", nullable = false, updatable = false)
    private UUID keyId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "alias", nullable = false)
    private String alias;

    @Column(name = "purpose", nullable = false, updatable = false)
    private String purpose;

    @Column(name = "algorithm", nullable = false)
    private String algorithm;

    @Column(name = "key_size", nullable = false)
    private int keySize;

    // ── Sólo SIGNING ──────────────────────────────────────────────────────────
    @Column(name = "wrapped_dek")
    private byte[] wrappedDek;

    @Column(name = "encrypted_material")
    private byte[] encryptedMaterial;

    @Column(name = "iv")
    private byte[] iv;

    @Column(name = "auth_tag")
    private byte[] authTag;

    /** Qué KEK envolvió la DEK. Permite rotar la KEK sin descifrar el material RSA. */
    @Column(name = "kek_id")
    private String kekId;

    // ── SIGNING (derivada) y VERIFICATION (de STP) ────────────────────────────
    @Column(name = "public_key_spki")
    private byte[] publicKeySpki;

    /** Huella SHA-256 de la llave pública. Es lo que se expone por API, nunca el material. */
    @Column(name = "fingerprint_sha256", nullable = false)
    private String fingerprintSha256;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to", nullable = false)
    private Instant validTo;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    protected StpCompanyKey() {
    }

    public static StpCompanyKey signingKey(UUID companyId, String alias, int keySize,
                                           byte[] wrappedDek, byte[] encryptedMaterial, byte[] iv,
                                           byte[] authTag, String kekId, byte[] publicKeySpki,
                                           String fingerprintSha256, Instant validFrom, Instant validTo,
                                           String createdBy) {
        StpCompanyKey key = base(companyId, alias, KeyPurpose.SIGNING, keySize,
                fingerprintSha256, validFrom, validTo, createdBy);
        key.wrappedDek = wrappedDek;
        key.encryptedMaterial = encryptedMaterial;
        key.iv = iv;
        key.authTag = authTag;
        key.kekId = kekId;
        key.publicKeySpki = publicKeySpki;
        return key;
    }

    public static StpCompanyKey verificationKey(UUID companyId, String alias, int keySize,
                                                byte[] publicKeySpki, String fingerprintSha256,
                                                Instant validFrom, Instant validTo, String createdBy) {
        StpCompanyKey key = base(companyId, alias, KeyPurpose.VERIFICATION, keySize,
                fingerprintSha256, validFrom, validTo, createdBy);
        key.publicKeySpki = publicKeySpki;
        return key;
    }

    private static StpCompanyKey base(UUID companyId, String alias, KeyPurpose purpose, int keySize,
                                      String fingerprintSha256, Instant validFrom, Instant validTo,
                                      String createdBy) {
        if (validTo == null || validFrom == null || !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("La vigencia de la llave es inválida");
        }
        StpCompanyKey key = new StpCompanyKey();
        key.keyId = UUID.randomUUID();
        key.companyId = companyId;
        key.alias = alias;
        key.purpose = purpose.name();
        key.algorithm = "SHA256withRSA";
        key.keySize = keySize;
        key.fingerprintSha256 = fingerprintSha256;
        key.validFrom = validFrom;
        key.validTo = validTo;
        key.status = KeyStatus.ACTIVE.name();
        key.createdAt = Instant.now();
        key.createdBy = createdBy;
        return key;
    }

    /** KY-06: sólo firma una llave activa y dentro de vigencia. */
    public boolean isUsableAt(Instant moment) {
        return KeyStatus.ACTIVE.name().equals(status)
                && !moment.isBefore(validFrom)
                && moment.isBefore(validTo);
    }

    /** KY-07: para alertar antes de que caduque. */
    public boolean expiresBefore(Instant moment) {
        return validTo.isBefore(moment);
    }

    public void retire() { this.status = KeyStatus.RETIRED.name(); }
    public void revoke() { this.status = KeyStatus.REVOKED.name(); }
    public void markRotating() { this.status = KeyStatus.ROTATING.name(); }

    public UUID getKeyId() { return keyId; }
    public UUID getCompanyId() { return companyId; }
    public String getAlias() { return alias; }
    public KeyPurpose getPurpose() { return KeyPurpose.valueOf(purpose); }
    public String getAlgorithm() { return algorithm; }
    public int getKeySize() { return keySize; }
    public byte[] getWrappedDek() { return wrappedDek; }
    public byte[] getEncryptedMaterial() { return encryptedMaterial; }
    public byte[] getIv() { return iv; }
    public byte[] getAuthTag() { return authTag; }
    public String getKekId() { return kekId; }
    public byte[] getPublicKeySpki() { return publicKeySpki; }
    public String getFingerprintSha256() { return fingerprintSha256; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidTo() { return validTo; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }

    /**
     * {@code toString} explícito y pobre. Sin esto, un log accidental de la entidad volcaría el
     * material cifrado y el kekId (KY-01).
     */
    @Override
    public String toString() {
        return "StpCompanyKey{keyId=" + keyId + ", companyId=" + companyId
                + ", purpose=" + purpose + ", status=" + status
                + ", fingerprint=" + fingerprintSha256 + "}";
    }
}
