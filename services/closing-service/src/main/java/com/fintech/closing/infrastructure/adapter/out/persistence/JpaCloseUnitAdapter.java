package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.application.port.out.CloseUnitRepository;
import com.fintech.closing.domain.CloseUnit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Clase y no interfaz: el CAS del claim y el insert en lote necesitan SQL nativo, y eso no cabe en
 * una firma derivada del nombre del método.
 *
 * <p><b>Aquí no hay un solo {@code FOR UPDATE}.</b> La lectura de candidatas es un {@code SELECT}
 * normal y la toma es un {@code UPDATE} condicional. La exclusión la da el candado de Redis; la
 * base sólo confirma quién llegó primero.
 */
@Repository
class JpaCloseUnitAdapter implements CloseUnitRepository {

    @PersistenceContext
    private EntityManager em;

    private final SpringDataCloseUnitRepository jpa;

    JpaCloseUnitAdapter(SpringDataCloseUnitRepository jpa) { this.jpa = jpa; }

    @Override
    @Transactional(readOnly = true)
    public List<CloseUnit> findPending(UUID runId, int offset, int limit) {
        return jpa.findPending(runId, org.springframework.data.domain.PageRequest.of(
                Math.max(offset, 0), Math.max(limit, 1)));
    }

    /**
     * CAS optimista. Devuelve 1 si esta llamada la tomó, 0 si otro pod se adelantó entre el
     * {@code SELECT} y este {@code UPDATE}. Cero es un resultado normal del reparto, no un error.
     */
    @Override
    @Transactional
    public int claim(UUID unitId, String owner, long fencingToken, Instant leaseExpiresAt) {
        Query q = em.createNativeQuery("""
                UPDATE closing.close_units
                   SET status = 'CLAIMED', lease_owner = :owner, fencing_token = :token,
                       lease_expires_at = :expires, attempts = attempts + 1
                 WHERE unit_id = :unitId AND status = 'PENDING'
                """);
        q.setParameter("owner", owner);
        q.setParameter("token", fencingToken);
        q.setParameter("expires", Timestamp.from(leaseExpiresAt));
        q.setParameter("unitId", unitId);
        return q.executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CloseUnit> findExpiredLeases(Instant now, int limit) {
        return jpa.findExpiredLeases(Timestamp.from(now),
                org.springframework.data.domain.PageRequest.of(0, Math.max(limit, 1)));
    }

    @Override
    @Transactional(readOnly = true)
    public int countByStatus(UUID runId, String status) {
        return jpa.countByRunIdAndStatus(runId, status);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CloseUnit> findById(UUID unitId) { return jpa.findById(unitId); }

    @Override
    @Transactional
    public CloseUnit save(CloseUnit unit) { return jpa.save(unit); }

    @Override
    @Transactional
    public int insertBatch(List<CloseUnit> units) {
        // `saveAll` con el id ya asignado hace un SELECT por fila para decidir insert o update.
        // Aquí siempre son nuevas, así que se persisten directo y se vacía por tandas para no
        // cargar el contexto entero — con 500 000 unidades eso sería el consumo de memoria.
        int n = 0;
        for (CloseUnit u : units) {
            em.persist(u);
            if (++n % 200 == 0) { em.flush(); em.clear(); }
        }
        em.flush();
        return units.size();
    }
}
