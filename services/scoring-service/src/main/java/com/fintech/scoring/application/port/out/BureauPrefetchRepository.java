package com.fintech.scoring.application.port.out;

import com.fintech.scoring.domain.BureauPrefetch;

import java.util.Optional;
import java.util.UUID;

public interface BureauPrefetchRepository {
    BureauPrefetch save(BureauPrefetch prefetch);
    Optional<BureauPrefetch> findById(UUID prefetchId);
    boolean existsActiveByProspectId(UUID prospectId);

    /**
     * El último prefetch de un prospecto, en el estado en que quedó.
     *
     * <p>{@link #existsActiveByProspectId} sólo dice si hay uno en vuelo, y eso basta para decidir
     * si esperar. Para <b>explicar</b> por qué no hay reporte hace falta la diferencia entre
     * «terminó en FAILED» y «nunca se pidió»: son dos problemas con dueños distintos, y salir por
     * el mismo log deja a quien lo lee sin saber qué hacer.
     */
    Optional<BureauPrefetch> findLatestByProspectId(UUID prospectId);
}
