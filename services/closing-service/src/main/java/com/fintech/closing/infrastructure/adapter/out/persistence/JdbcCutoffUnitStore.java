package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.closing.application.port.out.CutoffUnitStore;
import com.fintech.closing.application.port.out.PortfolioDaySnapshots;
import com.fintech.closing.domain.ClosePhase;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * El corte en lote, en SQL.
 *
 * <p>Ninguna de estas sentencias toma candados de fila sobre datos de otro servicio: todo es del
 * esquema {@code closing}. Planificar 100 000 cortes es un {@code INSERT … SELECT}; despacharlos,
 * páginas por llave; contarlos, un {@code GROUP BY}.
 */
@Repository
class JdbcCutoffUnitStore implements CutoffUnitStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcCutoffUnitStore(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public int materialize(UUID runId, LocalDate cutoffDate, Collection<String> distributorProductTypes,
                           Collection<UUID> heldAccounts) {
        // Un arreglo vacío en `= ANY` no casa con nada, que es lo correcto: sin productos de
        // distribuidora configurados, todos los titulares son clientes.
        String[] distribuidoras = distributorProductTypes == null ? new String[0]
                : distributorProductTypes.toArray(String[]::new);
        // Las cuentas que cargos dejó fuera del cierre del día nacen retenidas: su corte espera a su interés.
        String retenidas = heldAccounts == null || heldAccounts.isEmpty() ? "{}"
                : heldAccounts.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",", "{", "}"));
        // A loan settled or written off by D has no statement to cut on D: its cutoff is marked SKIPPED instead of
        // staying SCHEDULED forever (16 such cutoffs in the 2 Nov E2E, all of closed loans, read as missed cutoffs).
        jdbc.update("""
                UPDATE closing.cutoff_schedules c SET status = 'SKIPPED'
                  FROM closing.account_close_profiles p
                 WHERE p.credit_account_id = c.credit_account_id
                   AND c.cutoff_date = :fecha
                   AND c.status = 'SCHEDULED'
                   AND p.status IN ('SETTLED','WRITTEN_OFF','CLOSED','CANCELLED')
                   AND (p.terminal_since IS NULL OR p.terminal_since <= :fecha)
                """, new MapSqlParameterSource("fecha", cutoffDate));
        return jdbc.update("""
                INSERT INTO closing.close_units
                       (unit_id, run_id, unit_key, credit_account_id, product_type, product_behavior,
                        obligor_party_id, holder_kind, status, created_at)
                SELECT gen_random_uuid(), :runId, c.credit_account_id::text, c.credit_account_id,
                       p.product_type, p.product_behavior, p.obligor_party_id,
                       CASE WHEN p.product_type = ANY(:distribuidoras) THEN 'DISTRIBUTOR' ELSE 'CUSTOMER' END,
                       CASE WHEN c.credit_account_id = ANY(CAST(:retenidas AS uuid[])) THEN 'HELD' ELSE 'PENDING' END,
                       now()
                  FROM closing.cutoff_schedules c
                  JOIN closing.account_close_profiles p ON p.credit_account_id = c.credit_account_id
                 WHERE c.cutoff_date = :fecha
                   AND c.status = 'SCHEDULED'
                   -- Terminal al cierre de D, no hoy: la que se liquidó el 11 sí corta el 10.
                   AND (p.status NOT IN ('SETTLED','WRITTEN_OFF','CLOSED','CANCELLED') OR p.terminal_since > :fecha)
                ON CONFLICT (run_id, unit_key) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("fecha", cutoffDate)
                .addValue("distribuidoras", distribuidoras)
                .addValue("retenidas", retenidas));
    }

    @Override
    @Transactional
    public boolean release(UUID runId, UUID creditAccountId) {
        return jdbc.update("""
                UPDATE closing.close_units SET status = 'PENDING', dispatched_at = NULL
                 WHERE run_id = :runId AND credit_account_id = :a AND status = 'HELD'
                """, new MapSqlParameterSource().addValue("runId", runId).addValue("a", creditAccountId)) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> accountsWithoutSnapshot(UUID runId, int limit) {
        return jdbc.queryForList("""
                SELECT credit_account_id FROM closing.close_units
                 WHERE run_id = :runId AND days_delinquent_at_day IS NULL AND status IN ('PENDING', 'HELD', 'FAILED')
                 ORDER BY credit_account_id LIMIT :n
                """, new MapSqlParameterSource().addValue("runId", runId).addValue("n", Math.max(limit, 1)), UUID.class);
    }

    @Override
    @Transactional
    public int attachSnapshots(UUID runId, Collection<PortfolioDaySnapshots.AccountSnapshot> snapshots) {
        if (snapshots.isEmpty()) return 0;
        SqlParameterSource[] batch = snapshots.stream()
                .map(s -> new MapSqlParameterSource().addValue("runId", runId).addValue("a", s.creditAccountId())
                        .addValue("dpd", s.daysDelinquent()).addValue("p", s.principal())
                        .addValue("at", Timestamp.from(s.takenAt())))
                .toArray(SqlParameterSource[]::new);
        int[] rows = jdbc.batchUpdate("""
                UPDATE closing.close_units
                   SET days_delinquent_at_day = :dpd, principal_at_day = :p, snapshot_taken_at = :at
                 WHERE run_id = :runId AND credit_account_id = :a AND days_delinquent_at_day IS NULL
                """, batch);
        return Arrays.stream(rows).sum();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Breakdown> breakdown(UUID runId) {
        return jdbc.query("""
                SELECT product_type, product_behavior, holder_kind, COUNT(*) AS units
                  FROM closing.close_units
                 WHERE run_id = :runId
                 GROUP BY product_type, product_behavior, holder_kind
                 ORDER BY holder_kind, product_type
                """, new MapSqlParameterSource("runId", runId),
                (rs, i) -> new Breakdown(rs.getString("product_type"), rs.getString("product_behavior"),
                        rs.getString("holder_kind"), rs.getInt("units")));
    }

    @Override
    @Transactional
    public void saveBreakdown(UUID runId, List<Breakdown> breakdown) {
        jdbc.update("UPDATE closing.close_runs SET breakdown = CAST(:b AS jsonb) WHERE run_id = :runId",
                new MapSqlParameterSource().addValue("runId", runId).addValue("b", toJson(breakdown)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Breakdown> savedBreakdown(UUID runId) {
        List<String> filas = jdbc.queryForList(
                "SELECT breakdown::text FROM closing.close_runs WHERE run_id = :runId AND breakdown IS NOT NULL",
                new MapSqlParameterSource("runId", runId), String.class);
        if (filas.isEmpty()) return List.of();
        try {
            return json.readValue(filas.get(0), new TypeReference<List<Breakdown>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Desglose ilegible en la corrida " + runId, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Dispatchable> findDispatchable(UUID runId, Instant staleBefore, UUID after, int limit) {
        return jdbc.query("""
                SELECT unit_id, run_id, credit_account_id, obligor_party_id, product_type,
                       product_behavior, holder_kind
                  FROM closing.close_units
                 WHERE run_id = :runId
                   AND status = 'PENDING'
                   AND (dispatched_at IS NULL OR dispatched_at < :stale)
                   AND unit_id > :after
                 ORDER BY unit_id
                 LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("stale", Timestamp.from(staleBefore))
                .addValue("after", after == null ? new UUID(0L, 0L) : after)
                .addValue("limit", Math.max(limit, 1)),
                (rs, i) -> new Dispatchable(
                        rs.getObject("unit_id", UUID.class), rs.getObject("run_id", UUID.class),
                        rs.getObject("credit_account_id", UUID.class), rs.getObject("obligor_party_id", UUID.class),
                        rs.getString("product_type"), rs.getString("product_behavior"), rs.getString("holder_kind")));
    }

    @Override
    @Transactional
    public int markDispatched(Collection<UUID> unitIds, Instant at) {
        if (unitIds.isEmpty()) return 0;
        return jdbc.update("""
                UPDATE closing.close_units SET dispatched_at = :at
                 WHERE unit_id IN (:ids) AND status = 'PENDING'
                """, new MapSqlParameterSource().addValue("at", Timestamp.from(at)).addValue("ids", unitIds));
    }

    @Override
    @Transactional
    public int requeueFailed(UUID runId, int maxAttempts) {
        return jdbc.update("""
                UPDATE closing.close_units
                   SET status = 'PENDING', dispatched_at = NULL, lease_owner = NULL,
                       lease_expires_at = NULL, fencing_token = NULL
                 WHERE run_id = :runId AND status = 'FAILED' AND attempts < :max
                """, new MapSqlParameterSource().addValue("runId", runId).addValue("max", maxAttempts));
    }

    @Override
    @Transactional
    public int requeueAllFailed(UUID runId) {
        return jdbc.update("""
                UPDATE closing.close_units
                   SET status = 'PENDING', dispatched_at = NULL, lease_owner = NULL,
                       lease_expires_at = NULL, fencing_token = NULL, attempts = 0
                 WHERE run_id = :runId AND status = 'FAILED'
                """, new MapSqlParameterSource("runId", runId));
    }

    @Override
    @Transactional(readOnly = true)
    public StatusCounts counts(UUID runId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE status = 'PENDING') AS pending,
                       COUNT(*) FILTER (WHERE status = 'CLAIMED') AS claimed,
                       COUNT(*) FILTER (WHERE status = 'DONE')    AS done,
                       COUNT(*) FILTER (WHERE status = 'FAILED')  AS failed,
                       COUNT(*) FILTER (WHERE status = 'SKIPPED') AS skipped,
                       COUNT(*) FILTER (WHERE status = 'HELD')    AS held
                  FROM closing.close_units WHERE run_id = :runId
                """, new MapSqlParameterSource("runId", runId),
                (rs, i) -> new StatusCounts(rs.getInt("pending"), rs.getInt("claimed"), rs.getInt("done"),
                        rs.getInt("failed"), rs.getInt("skipped"), rs.getInt("held")));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Failure> failures(UUID runId, int limit) {
        return jdbc.query("""
                SELECT credit_account_id, product_type, attempts, last_error
                  FROM closing.close_units
                 WHERE run_id = :runId AND status = 'FAILED'
                 ORDER BY unit_id
                 LIMIT :limit
                """, new MapSqlParameterSource().addValue("runId", runId).addValue("limit", Math.max(limit, 1)),
                (rs, i) -> new Failure(rs.getObject("credit_account_id", UUID.class), rs.getString("product_type"),
                        rs.getInt("attempts"), rs.getString("last_error")));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> openRuns(ClosePhase phase) {
        return jdbc.queryForList("""
                SELECT run_id FROM closing.close_runs
                 WHERE phase = :phase AND status IN ('PLANNED','RUNNING')
                 ORDER BY business_date
                """, new MapSqlParameterSource("phase", phase.name()), UUID.class);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocalDate> pendingCutoffDates(LocalDate from, LocalDate toExclusive) {
        return jdbc.queryForList("""
                SELECT DISTINCT c.cutoff_date
                  FROM closing.cutoff_schedules c
                  JOIN closing.account_close_profiles p ON p.credit_account_id = c.credit_account_id
                 WHERE c.status = 'SCHEDULED'
                   AND c.cutoff_date >= :desde AND c.cutoff_date < :hasta
                   AND p.status NOT IN ('SETTLED','WRITTEN_OFF','CLOSED','CANCELLED')
                 ORDER BY c.cutoff_date
                """, new MapSqlParameterSource().addValue("desde", from).addValue("hasta", toExclusive),
                LocalDate.class);
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el desglose", e);
        }
    }
}
