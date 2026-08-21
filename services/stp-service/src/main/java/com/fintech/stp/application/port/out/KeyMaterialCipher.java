package com.fintech.stp.application.port.out;

import com.fintech.stp.domain.StpCompanyKey;

import java.time.Instant;
import java.util.UUID;

/**
 * Envuelve material criptográfico para poder guardarlo.
 *
 * <p>Separado de {@link SigningKeyProvider} a propósito: aquél lee y cachea en el camino caliente
 * de la firma, éste sólo se usa al dar de alta o rotar. Migrar a Vault o a un HSM cambia estas dos
 * implementaciones y nada más.
 */
public interface KeyMaterialCipher {

    /** Cifra una privada PKCS#8 con envelope encryption y deriva su pública para el fingerprint. */
    StpCompanyKey wrapSigningKey(UUID companyId, String alias, String pkcs8Base64,
                                 Instant validFrom, Instant validTo, String createdBy);

    /** Guarda una pública SPKI en claro — es pública. */
    StpCompanyKey storeVerificationKey(UUID companyId, String alias, String spkiBase64,
                                       Instant validFrom, Instant validTo, String createdBy);
}
