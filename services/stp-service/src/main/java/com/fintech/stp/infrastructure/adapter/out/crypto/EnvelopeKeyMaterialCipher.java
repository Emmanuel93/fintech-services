package com.fintech.stp.infrastructure.adapter.out.crypto;

import com.fintech.stp.application.port.out.KeyMaterialCipher;
import com.fintech.stp.domain.StpCompanyKey;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** Implementación de {@link KeyMaterialCipher} con envelope encryption sobre AES-256-GCM. */
@Component
public class EnvelopeKeyMaterialCipher implements KeyMaterialCipher {

    private static final String RSA = "RSA";

    private final EnvelopeCipher cipher = new EnvelopeCipher();
    private final KeyEncryptionKeyResolver kekResolver;

    public EnvelopeKeyMaterialCipher(KeyEncryptionKeyResolver kekResolver) {
        this.kekResolver = kekResolver;
    }

    @Override
    public StpCompanyKey wrapSigningKey(UUID companyId, String alias, String pkcs8Base64,
                                        Instant validFrom, Instant validTo, String createdBy) {
        byte[] pkcs8 = Base64.getDecoder().decode(pkcs8Base64.trim());
        try {
            PrivateKey privateKey = KeyFactory.getInstance(RSA).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            PublicKey publicKey = derivePublicKey(privateKey);

            String kekId = kekResolver.currentKekId();
            byte[] kek = kekResolver.resolve(kekId);
            SecretKey dek = cipher.newDataKey();

            EnvelopeCipher.Sealed material = cipher.seal(pkcs8, dek.getEncoded());
            EnvelopeCipher.Sealed wrappedDek = cipher.seal(dek.getEncoded(), kek);

            return StpCompanyKey.signingKey(companyId, alias, keySize(publicKey),
                    concat(wrappedDek), material.ciphertext(), material.iv(), material.authTag(),
                    kekId, publicKey.getEncoded(), fingerprint(publicKey),
                    validFrom, validTo, createdBy);
        } catch (GeneralSecurityException e) {
            // El mensaje no incluye nada del material (KY-01).
            throw new IllegalArgumentException("El material PKCS#8 no es una llave RSA válida", e);
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }

    @Override
    public StpCompanyKey storeVerificationKey(UUID companyId, String alias, String spkiBase64,
                                              Instant validFrom, Instant validTo, String createdBy) {
        byte[] spki = Base64.getDecoder().decode(spkiBase64.trim());
        try {
            PublicKey publicKey = KeyFactory.getInstance(RSA).generatePublic(new X509EncodedKeySpec(spki));
            return StpCompanyKey.verificationKey(companyId, alias, keySize(publicKey), spki,
                    fingerprint(publicKey), validFrom, validTo, createdBy);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("El material SPKI no es una llave pública RSA válida", e);
        }
    }

    /** Descifra el material privado. Sólo lo llama el proveedor de llaves, y nunca lo persiste. */
    byte[] unwrapSigningMaterial(StpCompanyKey key) {
        try {
            byte[] kek = kekResolver.resolve(key.getKekId());
            byte[] wrapped = key.getWrappedDek();
            // Formato de wrappedDek: iv(12) || tag(16) || ciphertext
            byte[] iv = Arrays.copyOfRange(wrapped, 0, 12);
            byte[] tag = Arrays.copyOfRange(wrapped, 12, 28);
            byte[] ciphertext = Arrays.copyOfRange(wrapped, 28, wrapped.length);
            byte[] dek = cipher.unseal(ciphertext, tag, iv, kek);
            try {
                return cipher.unseal(key.getEncryptedMaterial(), key.getAuthTag(), key.getIv(), dek);
            } finally {
                Arrays.fill(dek, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "No se pudo descifrar la llave " + key.getKeyId() + " con la KEK " + key.getKekId(), e);
        }
    }

    private static byte[] concat(EnvelopeCipher.Sealed sealed) {
        byte[] out = new byte[sealed.iv().length + sealed.authTag().length + sealed.ciphertext().length];
        System.arraycopy(sealed.iv(), 0, out, 0, sealed.iv().length);
        System.arraycopy(sealed.authTag(), 0, out, sealed.iv().length, sealed.authTag().length);
        System.arraycopy(sealed.ciphertext(), 0, out, sealed.iv().length + sealed.authTag().length,
                sealed.ciphertext().length);
        return out;
    }

    private static PublicKey derivePublicKey(PrivateKey privateKey) throws GeneralSecurityException {
        if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
            throw new GeneralSecurityException(
                    "Se requiere una llave RSA en formato CRT para derivar la pública");
        }
        return KeyFactory.getInstance(RSA)
                .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
    }

    private static int keySize(PublicKey publicKey) {
        return publicKey instanceof RSAPublicKey rsa ? rsa.getModulus().bitLength() : 0;
    }

    /** Huella SHA-256 del SPKI público. Es lo único que se expone por API. */
    private static String fingerprint(PublicKey publicKey) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded()));
    }
}
