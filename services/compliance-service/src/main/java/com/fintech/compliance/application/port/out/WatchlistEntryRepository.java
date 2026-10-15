package com.fintech.compliance.application.port.out;

import com.fintech.compliance.domain.WatchlistEntry;

import java.util.List;
import java.util.UUID;

public interface WatchlistEntryRepository {

    void saveAll(List<WatchlistEntry> batch);

    long countByVersionId(UUID versionId);

    /** Se usa al limpiar una carga fallida: filas de una versión que nunca se activó no sirven a nadie. */
    void deleteByVersionId(UUID versionId);
}
