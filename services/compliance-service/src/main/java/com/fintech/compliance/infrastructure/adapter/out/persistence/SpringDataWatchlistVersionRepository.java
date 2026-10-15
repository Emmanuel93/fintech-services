package com.fintech.compliance.infrastructure.adapter.out.persistence;

import com.fintech.compliance.domain.WatchlistVersion;
import com.fintech.compliance.domain.WatchlistVersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataWatchlistVersionRepository extends JpaRepository<WatchlistVersion, UUID> {

    Optional<WatchlistVersion> findByFileHash(String fileHash);

    Optional<WatchlistVersion> findBySourceCodeAndStatus(String sourceCode, WatchlistVersionStatus status);

    Optional<WatchlistVersion> findFirstByStatusAndActivatedAtAfterOrderByActivatedAtDesc(
            WatchlistVersionStatus status, java.time.Instant since);

    /**
     * La última carga que llegó a terminar, en cualquiera de sus tres formas de haber terminado.
     *
     * <p>Incluye `LOADED` a propósito: una versión frenada por salto de rechazo <b>sí</b> terminó de
     * leerse, y su tasa es la referencia legítima para la siguiente. Excluirla haría que dos cargas
     * malas seguidas se comparasen contra la última buena y la segunda pasara el filtro por poco.
     */
    @Query("""
            SELECT v FROM WatchlistVersion v
             WHERE v.sourceCode = :sourceCode
               AND v.status IN (
                   com.fintech.compliance.domain.WatchlistVersionStatus.ACTIVE,
                   com.fintech.compliance.domain.WatchlistVersionStatus.SUPERSEDED,
                   com.fintech.compliance.domain.WatchlistVersionStatus.LOADED)
             ORDER BY v.startedAt DESC
            """)
    List<WatchlistVersion> findFinished(@Param("sourceCode") String sourceCode);
}
