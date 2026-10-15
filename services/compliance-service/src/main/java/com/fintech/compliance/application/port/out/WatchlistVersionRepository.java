package com.fintech.compliance.application.port.out;

import com.fintech.compliance.domain.WatchlistVersion;

import java.util.Optional;

public interface WatchlistVersionRepository {

    /** La llave de la idempotencia: mismo contenido, misma carga. */
    Optional<WatchlistVersion> findByFileHash(String fileHash);

    /** La vigente de esa lista, si hay. */
    Optional<WatchlistVersion> findBySourceCodeAndStatus(String sourceCode,
                                                         com.fintech.compliance.domain.WatchlistVersionStatus status);

    /** La última que llegó a terminar, para comparar contra ella la tasa de rechazo. */
    Optional<WatchlistVersion> findLastFinished(String sourceCode);

    /**
     * Si se activó alguna lista después de ese momento.
     *
     * <p>Es lo que distingue «hay algo nuevo que mirar» de «toca la revisión periódica», y permite
     * que el registro de la corrida diga cuál de las dos fue — que es lo que después explica por qué
     * cambió el veredicto de alguien.
     */
    java.util.Optional<WatchlistVersion> findLastActivatedAfter(java.time.Instant since);

    WatchlistVersion save(WatchlistVersion version);
}
