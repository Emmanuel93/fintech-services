package com.fintech.stp.infrastructure.adapter.in.api.dto;

import com.fintech.stp.domain.StpCompanyKey;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadatos de una llave. <strong>Nunca material</strong> (KY-01): huella, vigencia y estado.
 * Que este record no tenga ningún campo de bytes es la garantía estructural.
 */
public record KeyMetadataResponse(
        UUID keyId,
        UUID companyId,
        String alias,
        String purpose,
        String algorithm,
        int keySize,
        String fingerprintSha256,
        Instant validFrom,
        Instant validTo,
        String status,
        Instant createdAt,
        String createdBy
) {

    public static KeyMetadataResponse from(StpCompanyKey key) {
        return new KeyMetadataResponse(key.getKeyId(), key.getCompanyId(), key.getAlias(),
                key.getPurpose().name(), key.getAlgorithm(), key.getKeySize(),
                key.getFingerprintSha256(), key.getValidFrom(), key.getValidTo(),
                key.getStatus(), key.getCreatedAt(), key.getCreatedBy());
    }
}
