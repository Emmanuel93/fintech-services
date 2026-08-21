package com.fintech.stp.application.port.out;

import com.fintech.stp.domain.KeyPurpose;
import com.fintech.stp.domain.StpCompanyKey;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Optional;
import java.util.UUID;

/**
 * Custodia de material criptográfico por empresa.
 *
 * <p>Simétrico a propósito: firmar lo que sale y verificar lo que entra son la misma
 * responsabilidad. Quién descifra, dónde vive la KEK y cómo se cachea es problema del adaptador.
 *
 * <p>Cambiar de envelope encryption a Vault/KeyVault/HSM es cambiar una implementación de esta
 * interfaz. Nada más.
 */
public interface SigningKeyProvider {

    /** Llave privada de firma de la empresa. KY-06: lanza si está vencida o no existe. */
    PrivateKey activeSigningKey(UUID companyId);

    /** Llave pública de STP, para verificar el sello de sus respuestas (SO-04). */
    Optional<PublicKey> activeVerificationKey(UUID companyId);

    /**
     * Pública derivada de nuestra propia llave de firma. La usa el stub de ambientes bajos para
     * verificar lo que le enviamos — así el camino de firma se ejercita de verdad en local.
     */
    Optional<PublicKey> signingPublicKey(UUID companyId);

    /** Metadatos sin material (KY-01). */
    StpCompanyKey activeKeyMetadata(UUID companyId, KeyPurpose purpose);

    /** Invalida la caché tras una rotación. */
    void evict(UUID companyId);
}
