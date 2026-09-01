package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.application.port.out.CloseSealRepository;
import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseSeal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

@Repository
class JpaCloseSealAdapter implements CloseSealRepository {

    @PersistenceContext private EntityManager em;
    private final SpringDataCloseSealRepository jpa;

    JpaCloseSealAdapter(SpringDataCloseSealRepository jpa) { this.jpa = jpa; }

    @Override
    public Optional<CloseSeal> find(LocalDate d, ClosePhase phase, String scopeKey) {
        return jpa.findByBusinessDateAndPhaseAndScopeKey(d, phase.name(), scopeKey);
    }

    @Override public CloseSeal save(CloseSeal seal) { return jpa.save(seal); }

    /**
     * Las cifras del sello, agregadas <b>en la base</b>.
     *
     * <p>Sumar recorriendo cuentas en memoria sería repetir el error que este servicio corrige, y
     * además en la parte serial del cierre — la que manda el techo de escalamiento.
     */
    @Override
    @Transactional(readOnly = true)
    public Totales aggregateActive() {
        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT COUNT(*)::int,
                       COALESCE(SUM(principal_balance), 0),
                       COALESCE(SUM(total_debt - principal_balance), 0),
                       0::numeric,
                       COALESCE(SUM(total_debt), 0)
                  FROM closing.account_close_profiles
                 WHERE status NOT IN ('SETTLED','WRITTEN_OFF','CLOSED')
                """).getSingleResult();

        return new Totales(((Number) row[0]).intValue(),
                (BigDecimal) row[1], (BigDecimal) row[2], (BigDecimal) row[3], (BigDecimal) row[4]);
    }
}
