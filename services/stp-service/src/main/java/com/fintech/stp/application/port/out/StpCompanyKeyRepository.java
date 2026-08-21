package com.fintech.stp.application.port.out;

import com.fintech.stp.domain.KeyPurpose;
import com.fintech.stp.domain.StpCompanyKey;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StpCompanyKeyRepository {

    Optional<StpCompanyKey> findActiveByCompanyIdAndPurpose(UUID companyId, KeyPurpose purpose);

    Optional<StpCompanyKey> findById(UUID keyId);

    List<StpCompanyKey> findByCompanyId(UUID companyId);

    /** KY-07: llaves activas que caducan antes de un momento dado. */
    List<StpCompanyKey> findActiveExpiringBefore(Instant threshold);

    StpCompanyKey save(StpCompanyKey key);

    /**
     * Igual que {@link #save}, pero sincroniza con la base de inmediato.
     *
     * <p>Hace falta al rotar: Hibernate ejecuta los {@code INSERT} antes que los {@code UPDATE}
     * dentro de una misma transacción, así que retirar la llave anterior y dar de alta la nueva sin
     * flush intermedio choca contra el índice único parcial de "una activa por (empresa, propósito)"
     * — y la rotación se vuelve imposible.
     */
    StpCompanyKey saveAndFlush(StpCompanyKey key);
}
