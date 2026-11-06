package com.fintech.statement.infrastructure.adapter.out.persistence;

import com.fintech.statement.application.port.out.StatementBatchRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Los dos eventos de la corrida viajan por tópicos distintos y pueden llegar en cualquier orden: los
 * dos hacen upsert sobre la misma fila.
 */
@Repository
class JdbcStatementBatchRepository implements StatementBatchRepository {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcStatementBatchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void planned(UUID runId, LocalDate businessDate, int expected, String breakdownJson) {
        jdbc.update("""
                INSERT INTO statement.statement_batches (run_id, business_date, expected, breakdown, planned_at)
                VALUES (:run, :date, :expected, CAST(:breakdown AS jsonb), now())
                ON CONFLICT (run_id) DO UPDATE
                   SET expected = EXCLUDED.expected, breakdown = EXCLUDED.breakdown, planned_at = now()
                """, new MapSqlParameterSource().addValue("run", runId).addValue("date", businessDate)
                .addValue("expected", expected).addValue("breakdown", breakdownJson));
    }

    @Override
    public void cutoffCompleted(UUID runId, LocalDate businessDate, String cutoffStatus, int done, int failed) {
        jdbc.update("""
                INSERT INTO statement.statement_batches
                       (run_id, business_date, cutoff_status, cutoff_done, cutoff_failed, cutoff_completed_at)
                VALUES (:run, :date, :status, :done, :failed, now())
                ON CONFLICT (run_id) DO UPDATE
                   SET cutoff_status = EXCLUDED.cutoff_status, cutoff_done = EXCLUDED.cutoff_done,
                       cutoff_failed = EXCLUDED.cutoff_failed, cutoff_completed_at = now()
                """, new MapSqlParameterSource().addValue("run", runId).addValue("date", businessDate)
                .addValue("status", cutoffStatus).addValue("done", done).addValue("failed", failed));
    }

    @Override
    public Optional<Batch> find(UUID runId) {
        return jdbc.query("SELECT * FROM statement.statement_batches WHERE run_id = :run",
                new MapSqlParameterSource("run", runId), BATCH).stream().findFirst();
    }

    @Override
    public Optional<Batch> findByDate(LocalDate businessDate) {
        return jdbc.query("""
                SELECT * FROM statement.statement_batches WHERE business_date = :date
                 ORDER BY planned_at DESC NULLS LAST LIMIT 1
                """, new MapSqlParameterSource("date", businessDate), BATCH).stream().findFirst();
    }

    private static final RowMapper<Batch> BATCH = (rs, i) -> new Batch(
            rs.getObject("run_id", UUID.class), rs.getObject("business_date", LocalDate.class), rs.getInt("expected"),
            rs.getString("breakdown"), rs.getString("cutoff_status"), rs.getInt("cutoff_done"),
            rs.getInt("cutoff_failed"), instant(rs.getTimestamp("planned_at")),
            instant(rs.getTimestamp("cutoff_completed_at")));

    private static java.time.Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }
}
