package com.fintech.compliance.infrastructure.adapter.out.persistence;

import com.fintech.compliance.application.port.out.WatchlistSearchPort;
import com.fintech.compliance.domain.WatchlistEntry;
import com.fintech.compliance.domain.WatchlistVersionStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Las tres búsquedas contra las versiones vigentes.
 *
 * <p>El parecido usa <b>`pg_trgm`</b>, que ya está en la plataforma (`party` y `credit-portfolio` lo
 * usan para sus búsquedas). Se resuelve en la base y no en Java por una razón de tamaño: comparar en
 * memoria obligaría a traer cientos de miles de filas por cada persona que se registra.
 */
@Component
class JpaWatchlistSearchAdapter implements WatchlistSearchPort {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> activeVersionIds() {
        return em.createQuery("""
                        SELECT v.versionId FROM WatchlistVersion v WHERE v.status = :st
                        """, UUID.class)
                .setParameter("st", WatchlistVersionStatus.ACTIVE)
                .getResultList().stream().collect(Collectors.toSet());
    }

    @Override
    @Transactional(readOnly = true)
    public List<WatchlistEntry> findByDocument(String curp, String rfc, Set<UUID> versions) {
        if (versions.isEmpty()) return List.of();
        // Dos consultas y no una con `:curp IS NOT NULL`.
        //
        // Esa forma compila y **falla al ejecutarse**: Postgres no puede inferir el tipo de un
        // parámetro que sólo aparece en `? IS NOT NULL`, y responde con un error de gramática SQL
        // que no menciona la causa. Separarlas quita el problema de raíz y además se lee mejor.
        //
        // `UPPER` a los dos lados: los documentos se guardan normalizados al cargar, pero quien
        // consulta puede no hacerlo, y una comparación sensible a mayúsculas no encontraría nada.
        List<WatchlistEntry> found = new ArrayList<>();
        String c = blankToNull(curp);
        String r = blankToNull(rfc);

        if (c != null) found.addAll(byField("curp", c, versions));
        if (r != null) found.addAll(byField("rfc",  r, versions));

        // La misma entrada puede traer CURP y RFC de la persona; se reporta una vez.
        return found.stream().collect(Collectors.toMap(
                        WatchlistEntry::getEntryId, e -> e, (a, b) -> a, LinkedHashMap::new))
                .values().stream().toList();
    }

    private List<WatchlistEntry> byField(String field, String value, Set<UUID> versions) {
        return em.createQuery("""
                        SELECT e FROM WatchlistEntry e
                         WHERE e.versionId IN :versions AND UPPER(e.%s) = UPPER(:value)
                        """.formatted(field), WatchlistEntry.class)
                .setParameter("versions", versions)
                .setParameter("value", value)
                .getResultList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WatchlistEntry> findByCurpRoot(Set<String> roots, Set<UUID> versions) {
        if (versions.isEmpty() || roots == null || roots.isEmpty()) return List.of();

        // Las dos columnas en un `OR` y no una tabla hija: son exactamente dos —las dos lecturas del
        // orden de apellidos— y Postgres resuelve el `OR` con un bitmap de los dos índices. Una
        // tabla aparte añadiría un join y una segunda escritura por fila en cargas de cientos de
        // miles, a cambio de una flexibilidad que la derivación no necesita.
        return em.createQuery("""
                        SELECT e FROM WatchlistEntry e
                         WHERE e.versionId IN :versions
                           AND (e.curpRootA IN :roots OR e.curpRootB IN :roots)
                        """, WatchlistEntry.class)
                .setParameter("versions", versions)
                .setParameter("roots", roots)
                .getResultList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WatchlistEntry> findBySortedName(String sortedName, Set<UUID> versions) {
        if (versions.isEmpty() || sortedName == null || sortedName.isBlank()) return List.of();
        return em.createQuery("""
                        SELECT e FROM WatchlistEntry e
                         WHERE e.versionId IN :versions AND e.sortedName = :sorted
                        """, WatchlistEntry.class)
                .setParameter("versions", versions)
                .setParameter("sorted", sortedName)
                .getResultList();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<ScoredEntry> findSimilarNames(String normalizedName, double minSimilarity, Set<UUID> versions) {
        if (versions.isEmpty() || normalizedName == null || normalizedName.isBlank()) return List.of();

        // Se excluye la coincidencia exacta: ésa ya la trajo `findBySortedName` con una evidencia
        // más fuerte, y volver a traerla sólo añadiría trabajo de deduplicación.
        List<Object[]> filas = em.createNativeQuery("""
                        SELECT e.entry_id, similarity(e.normalized_name, :name) AS sim
                          FROM compliance.watchlist_entries e
                         WHERE e.version_id IN (:versions)
                           AND e.normalized_name <> :name
                           AND similarity(e.normalized_name, :name) >= :minSim
                         ORDER BY sim DESC
                         LIMIT 50
                        """)
                .setParameter("name", normalizedName)
                .setParameter("versions", versions)
                .setParameter("minSim", minSimilarity)
                .getResultList();

        return filas.stream()
                .map(f -> new ScoredEntry(
                        em.find(WatchlistEntry.class, (UUID) f[0]),
                        ((Number) f[1]).doubleValue()))
                .filter(s -> s.entry() != null)
                .toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
