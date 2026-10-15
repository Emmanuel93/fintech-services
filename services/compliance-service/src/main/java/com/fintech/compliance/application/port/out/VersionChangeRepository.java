package com.fintech.compliance.application.port.out;

import com.fintech.compliance.domain.WatchlistVersionChange;

import java.util.List;
import java.util.UUID;

/**
 * El detalle de qué cambió en cada actualización de una lista.
 *
 * <p>El diff se calcula <b>en la base</b> y no en Java: son cientos de miles de filas por versión, y
 * traerlas a memoria para compararlas sería el mismo error que traerlas para cruzarlas.
 */
public interface VersionChangeRepository {

    /**
     * Calcula y guarda el diff de una versión contra la anterior de la misma lista.
     *
     * @return cuántos cambios se registraron
     */
    int computeAndStore(UUID versionId, UUID previousVersionId);

    /** La primera carga de una lista: una sola fila, no una por entrada. */
    void recordInitialLoad(WatchlistVersionChange initial);

    List<WatchlistVersionChange> findByVersion(UUID versionId, String changeType, int limit);

    /** Cuántos de cada tipo, para el resumen de la actualización. */
    java.util.Map<String, Long> countByType(UUID versionId);
}
