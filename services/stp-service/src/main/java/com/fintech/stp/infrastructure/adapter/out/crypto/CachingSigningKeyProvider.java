package com.fintech.stp.infrastructure.adapter.out.crypto;

import com.fintech.stp.application.port.out.SigningKeyProvider;
import com.fintech.stp.application.port.out.StpCompanyKeyRepository;
import com.fintech.stp.domain.KeyPurpose;
import com.fintech.stp.domain.SigningKeyNotAvailableException;
import com.fintech.stp.domain.StpCompanyKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resuelve y cachea llaves por empresa.
 *
 * <p>El legado releía el keystore del disco y lo parseaba en cada firma. Aquí se descifra una vez
 * y se guarda en memoria; la caché se invalida explícitamente al rotar (KY-05).
 *
 * <p>La caché nunca se serializa ni se persiste: es un {@code ConcurrentHashMap} de objetos
 * {@code PrivateKey} que muere con el proceso.
 */
@Component
public class CachingSigningKeyProvider implements SigningKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(CachingSigningKeyProvider.class);
    private static final String RSA = "RSA";

    private final StpCompanyKeyRepository keyRepository;
    private final EnvelopeKeyMaterialCipher cipher;

    /**
     * Las tres cachés guardan la fecha de caducidad junto a la llave.
     *
     * <p>Sin ella, la comprobación de vigencia sólo corría en el primer acceso: un proceso levantado
     * desde ayer seguiría firmando con una llave que venció de madrugada hasta que alguien lo
     * reiniciara. KY-06 tiene que valer en todos los caminos de firma, no sólo en el que casualmente
     * pide los metadatos antes.
     */
    private record Cached<T>(T material, Instant validTo) {
        boolean isUsableAt(Instant moment) { return validTo == null || moment.isBefore(validTo); }
    }

    private final Map<UUID, Cached<PrivateKey>> signingCache = new ConcurrentHashMap<>();
    private final Map<UUID, Cached<PublicKey>> verificationCache = new ConcurrentHashMap<>();
    private final Map<UUID, Cached<PublicKey>> signingPublicCache = new ConcurrentHashMap<>();

    public CachingSigningKeyProvider(StpCompanyKeyRepository keyRepository,
                                      EnvelopeKeyMaterialCipher cipher) {
        this.keyRepository = keyRepository;
        this.cipher = cipher;
    }

    @Override
    public PrivateKey activeSigningKey(UUID companyId) {
        Instant now = Instant.now();
        Cached<PrivateKey> cached = signingCache.get(companyId);
        if (cached != null && cached.isUsableAt(now)) {
            return cached.material();
        }
        signingCache.remove(companyId);

        // activeKeyMetadata ya lanza si no hay llave usable ahora mismo.
        StpCompanyKey metadata = activeKeyMetadata(companyId, KeyPurpose.SIGNING);
        byte[] pkcs8 = cipher.unwrapSigningMaterial(metadata);
        try {
            PrivateKey key = KeyFactory.getInstance(RSA).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            signingCache.put(companyId, new Cached<>(key, metadata.getValidTo()));
            return key;
        } catch (Exception e) {
            throw new SigningKeyNotAvailableException(
                    "No se pudo reconstruir la llave de firma de la empresa " + companyId);
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }

    @Override
    public Optional<PublicKey> activeVerificationKey(UUID companyId) {
        Instant now = Instant.now();
        Cached<PublicKey> cached = verificationCache.get(companyId);
        if (cached != null && cached.isUsableAt(now)) {
            return Optional.of(cached.material());
        }
        verificationCache.remove(companyId);

        return keyRepository.findActiveByCompanyIdAndPurpose(companyId, KeyPurpose.VERIFICATION)
                .filter(key -> key.isUsableAt(now))
                .map(key -> {
                    PublicKey publicKey = toPublicKey(companyId, key.getPublicKeySpki());
                    verificationCache.put(companyId, new Cached<>(publicKey, key.getValidTo()));
                    return publicKey;
                });
    }

    @Override
    public Optional<PublicKey> signingPublicKey(UUID companyId) {
        Instant now = Instant.now();
        Cached<PublicKey> cached = signingPublicCache.get(companyId);
        if (cached != null && cached.isUsableAt(now)) {
            return Optional.of(cached.material());
        }
        signingPublicCache.remove(companyId);

        return keyRepository.findActiveByCompanyIdAndPurpose(companyId, KeyPurpose.SIGNING)
                .filter(key -> key.isUsableAt(now))
                .map(key -> {
                    PublicKey publicKey = toPublicKey(companyId, key.getPublicKeySpki());
                    signingPublicCache.put(companyId, new Cached<>(publicKey, key.getValidTo()));
                    return publicKey;
                });
    }

    @Override
    public StpCompanyKey activeKeyMetadata(UUID companyId, KeyPurpose purpose) {
        StpCompanyKey key = keyRepository.findActiveByCompanyIdAndPurpose(companyId, purpose)
                .orElseThrow(() -> new SigningKeyNotAvailableException(
                        "La empresa " + companyId + " no tiene llave " + purpose + " activa"));
        // KY-06: una llave vencida no firma. La orden falla; no se degrada en silencio.
        if (!key.isUsableAt(Instant.now())) {
            throw new SigningKeyNotAvailableException(
                    "La llave " + purpose + " de la empresa " + companyId
                            + " no está vigente (validTo=" + key.getValidTo() + ")");
        }
        return key;
    }

    @Override
    public void evict(UUID companyId) {
        signingCache.remove(companyId);
        verificationCache.remove(companyId);
        signingPublicCache.remove(companyId);
        log.info("Signing key cache evicted for companyId={}", companyId);
    }

    private static PublicKey toPublicKey(UUID companyId, byte[] spki) {
        try {
            return KeyFactory.getInstance(RSA).generatePublic(new X509EncodedKeySpec(spki));
        } catch (Exception e) {
            throw new SigningKeyNotAvailableException(
                    "No se pudo reconstruir la llave pública de la empresa " + companyId);
        }
    }
}
