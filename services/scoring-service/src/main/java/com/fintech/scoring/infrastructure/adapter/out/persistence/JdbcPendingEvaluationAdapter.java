package com.fintech.scoring.infrastructure.adapter.out.persistence;

import com.fintech.scoring.application.port.out.PendingEvaluationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * SQL a mano y no JPA: el reclamo es un {@code UPDATE ... RETURNING}, y es justo lo que lo hace
 * atómico. Con leer-y-luego-marcar, dos hilos leerían la misma fila y la evaluarían los dos.
 */
@Repository
class JdbcPendingEvaluationAdapter implements PendingEvaluationRepository {

    private final JdbcTemplate jdbc;

    JdbcPendingEvaluationAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void park(PendingEvaluation p) {
        jdbc.update("""
                INSERT INTO scoring.pending_evaluations
                       (application_id, prospect_id, prospect_type, product_type, reason)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (application_id) DO UPDATE
                   SET status = 'WAITING', reason = EXCLUDED.reason, parked_at = NOW(), resumed_at = NULL
                """, p.applicationId(), p.prospectId(), p.prospectType(), p.productType(), p.reason());
    }

    @Override
    public List<PendingEvaluation> claimWaitingFor(UUID prospectId) {
        return jdbc.query("""
                UPDATE scoring.pending_evaluations
                   SET status = 'RESUMED', resumed_at = NOW()
                 WHERE prospect_id = ? AND status = 'WAITING'
                RETURNING application_id, prospect_id, prospect_type, product_type, reason
                """,
                (rs, i) -> new PendingEvaluation(
                        rs.getObject("application_id", UUID.class),
                        rs.getObject("prospect_id", UUID.class),
                        rs.getString("prospect_type"),
                        rs.getString("product_type"),
                        rs.getString("reason")),
                prospectId);
    }
}
