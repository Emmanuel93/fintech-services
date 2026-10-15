package com.fintech.compliance.infrastructure.adapter.out.persistence;

import com.fintech.compliance.application.port.out.VersionChangeRepository;
import com.fintech.compliance.domain.WatchlistVersionChange;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

interface SpringDataVersionChangeRepository extends JpaRepository<WatchlistVersionChange, UUID> {
    List<WatchlistVersionChange> findByVersionId(UUID versionId, PageRequest page);
    List<WatchlistVersionChange> findByVersionIdAndChangeType(
            UUID versionId, com.fintech.compliance.domain.VersionChangeType type, PageRequest page);
}

@Component
class JpaVersionChangeAdapter implements VersionChangeRepository {

    @PersistenceContext
    private EntityManager em;

    private final SpringDataVersionChangeRepository repo;

    JpaVersionChangeAdapter(SpringDataVersionChangeRepository repo) { this.repo = repo; }

    /**
     * Tres sentencias de conjunto, una por tipo de cambio.
     *
     * <p>Se resuelve en la base a propósito: comparar dos versiones de cientos de miles de filas en
     * memoria exigiría traerlas enteras dos veces. Con índice sobre {@code (version_id,
     * identity_key)} esto es una unión, no un recorrido.
     */
    @Override
    @Transactional
    public int computeAndStore(UUID versionId, UUID previousVersionId) {
        int total = 0;

        // ALTAS — el cambio que puede señalar a alguien que ayer estaba limpio.
        total += em.createNativeQuery("""
                INSERT INTO compliance.watchlist_version_changes
                    (change_id, version_id, previous_version_id, change_type, identity_key, listed_name, program)
                SELECT gen_random_uuid(), :nueva, :vieja, 'ADDED', n.identity_key, n.raw_name, n.program
                  FROM compliance.watchlist_entries n
                 WHERE n.version_id = :nueva
                   AND NOT EXISTS (SELECT 1 FROM compliance.watchlist_entries o
                                    WHERE o.version_id = :vieja AND o.identity_key = n.identity_key)
                """).setParameter("nueva", versionId).setParameter("vieja", previousVersionId)
                .executeUpdate();

        // BAJAS — pueden despejar a alguien que llevaba meses bloqueado.
        total += em.createNativeQuery("""
                INSERT INTO compliance.watchlist_version_changes
                    (change_id, version_id, previous_version_id, change_type, identity_key, listed_name, program)
                SELECT gen_random_uuid(), :nueva, :vieja, 'REMOVED', o.identity_key, o.raw_name, o.program
                  FROM compliance.watchlist_entries o
                 WHERE o.version_id = :vieja
                   AND NOT EXISTS (SELECT 1 FROM compliance.watchlist_entries n
                                    WHERE n.version_id = :nueva AND n.identity_key = o.identity_key)
                """).setParameter("nueva", versionId).setParameter("vieja", previousVersionId)
                .executeUpdate();

        // MODIFICADAS — misma identidad, contenido distinto: corrigieron una fecha, cambió el programa.
        total += em.createNativeQuery("""
                INSERT INTO compliance.watchlist_version_changes
                    (change_id, version_id, previous_version_id, change_type, identity_key, listed_name, program, detail)
                SELECT gen_random_uuid(), :nueva, :vieja, 'MODIFIED', n.identity_key, n.raw_name, n.program,
                       'programa: ' || COALESCE(o.program, '—') || ' → ' || COALESCE(n.program, '—')
                  FROM compliance.watchlist_entries n
                  JOIN compliance.watchlist_entries o
                    ON o.version_id = :vieja AND o.identity_key = n.identity_key
                 WHERE n.version_id = :nueva
                   AND n.content_hash <> o.content_hash
                """).setParameter("nueva", versionId).setParameter("vieja", previousVersionId)
                .executeUpdate();

        return total;
    }

    @Override
    @Transactional
    public void recordInitialLoad(WatchlistVersionChange initial) {
        repo.save(initial);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WatchlistVersionChange> findByVersion(UUID versionId, String changeType, int limit) {
        PageRequest page = PageRequest.of(0, limit);
        return changeType == null || changeType.isBlank()
                ? repo.findByVersionId(versionId, page)
                : repo.findByVersionIdAndChangeType(versionId,
                        com.fintech.compliance.domain.VersionChangeType.valueOf(changeType), page);
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Long> countByType(UUID versionId) {
        List<Object[]> filas = em.createNativeQuery("""
                SELECT change_type, COUNT(*) FROM compliance.watchlist_version_changes
                 WHERE version_id = :v GROUP BY change_type
                """).setParameter("v", versionId).getResultList();

        Map<String, Long> out = new HashMap<>();
        for (Object[] f : filas) out.put((String) f[0], ((Number) f[1]).longValue());
        return out;
    }
}
