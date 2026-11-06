package com.fintech.charges.infrastructure.adapter.out.persistence;

import com.fintech.charges.application.port.out.AccrualUnitStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Las unidades del cierre, en SQL. Planificar 100 000 cuentas es un {@code INSERT … SELECT}; todo lo
 * demás son actualizaciones condicionales sobre la llave (día, cuenta) o sobre el índice de abiertas.
 */
@Repository
class JdbcAccrualUnitStore implements AccrualUnitStore {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcAccrualUnitStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int plan(LocalDate businessDate, Instant endOfDay) {
        // An account with an earlier unclosed day is born HELD: today's interest cannot overtake the one
        // it is missing. The accrual conditions are AccrualSchedule.needsAccrual in SQL, and the moratorium
        // one comes from the snapshot (overdue principal, oldest due date + grace), not from the live flag.
        // No snapshot row for a live schedule means the portfolio no longer counts that account: the unit
        // is born QUARANTINED with the reason, so it shows up among the accounts in trouble.
        return jdbc.update("""
                INSERT INTO charges.accrual_units (business_date, credit_account_id, schedule_id, status, held_reason,
                                                   basis_principal, basis_overdue_principal, basis_oldest_due_date, basis_version,
                                                   basis_scheduled_interest, basis_scheduled_tax)
                SELECT :d, s.credit_account_id, s.schedule_id,
                       CASE WHEN f.credit_account_id IS NULL THEN 'QUARANTINED'
                            WHEN p.pendiente IS NULL THEN 'PENDING' ELSE 'HELD' END,
                       CASE WHEN f.credit_account_id IS NULL
                                 THEN 'cartera no incluyó la cuenta en su foto del ' || :d || ': ¿sigue viva? revisar su calendario'
                            WHEN p.pendiente IS NULL THEN NULL ELSE 'falta cerrar su día ' || p.pendiente END,
                       coalesce(f.interest_bearing_principal, f.principal), f.overdue_principal, f.oldest_due_date, f.balance_version,
                       f.scheduled_interest, f.scheduled_tax
                  FROM charges.accrual_schedules s
                  LEFT JOIN charges.account_day_snapshots f
                         ON f.business_date = :d AND f.credit_account_id = s.credit_account_id
                  LEFT JOIN LATERAL (
                       SELECT min(u.business_date) AS pendiente FROM charges.accrual_units u
                        WHERE u.credit_account_id = s.credit_account_id
                          AND u.business_date < :d AND u.status <> 'DONE') p ON true
                 WHERE s.status = 'ACTIVE'
                   AND s.created_at < :fin
                   AND (((s.accrual_start_date IS NULL OR s.accrual_start_date <= :d)
                         AND (s.last_accrual_date IS NULL OR s.last_accrual_date < :d))
                        OR (f.overdue_principal > 0
                            AND f.oldest_due_date + s.grace_period_days <= :d
                            AND (s.last_moratorium_accrual_date IS NULL OR s.last_moratorium_accrual_date < :d)))
                ON CONFLICT (business_date, credit_account_id) DO NOTHING""",
                new MapSqlParameterSource("d", businessDate).addValue("fin", Timestamp.from(endOfDay)));
    }

    @Override
    public Optional<UnitDetail> detail(LocalDate businessDate, UUID creditAccountId) {
        return jdbc.query("""
                SELECT * FROM charges.accrual_units WHERE business_date = :d AND credit_account_id = :a""",
                key(businessDate, creditAccountId),
                (rs, i) -> new UnitDetail(rs.getObject("business_date", LocalDate.class),
                        rs.getObject("credit_account_id", UUID.class), rs.getObject("schedule_id", UUID.class),
                        rs.getString("status"), rs.getInt("attempts"),
                        rs.getString("held_reason") != null ? rs.getString("held_reason") : rs.getString("last_error"),
                        basis(rs), rs.getObject("basis_version", Long.class),
                        new AccountTotals(rs.getInt("ordinary_charges"), nz(rs.getBigDecimal("ordinary_amount")),
                                rs.getInt("moratorium_charges"), nz(rs.getBigDecimal("moratorium_amount"))),
                        rs.getBoolean("late"),
                        rs.getTimestamp("dispatched_at") != null ? rs.getTimestamp("dispatched_at").toInstant() : null,
                        rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null))
                .stream().findFirst();
    }

    private static Basis basis(ResultSet rs) throws SQLException {
        return new Basis(rs.getBigDecimal("basis_principal"), rs.getBigDecimal("basis_overdue_principal"),
                rs.getObject("basis_oldest_due_date", LocalDate.class), rs.getBigDecimal("basis_scheduled_interest"),
                rs.getBigDecimal("basis_scheduled_tax"));
    }

    @Override
    public Optional<Claimed> claim(LocalDate businessDate, UUID creditAccountId, Duration lease) {
        return jdbc.query("""
                UPDATE charges.accrual_units
                   SET status = 'CLAIMED', attempts = attempts + 1,
                       fencing_token = nextval('charges.accrual_unit_fencing'),
                       lease_expires_at = now() + make_interval(secs => :lease)
                 WHERE business_date = :d AND credit_account_id = :a AND status = 'PENDING'
                RETURNING schedule_id, fencing_token, basis_principal, basis_overdue_principal, basis_oldest_due_date,
                          basis_scheduled_interest, basis_scheduled_tax""",
                key(businessDate, creditAccountId).addValue("lease", lease.toSeconds()),
                (rs, i) -> new Claimed(rs.getObject("schedule_id", UUID.class), rs.getLong("fencing_token"), basis(rs)))
                .stream().findFirst();
    }

    @Override
    public boolean complete(LocalDate businessDate, UUID creditAccountId, long fencingToken, AccountTotals t) {
        return jdbc.update("""
                UPDATE charges.accrual_units u
                   SET status = 'DONE', completed_at = now(), lease_expires_at = NULL, last_error = NULL,
                       held_reason = NULL,
                       ordinary_charges = :oc, ordinary_amount = :oa,
                       moratorium_charges = :mc, moratorium_amount = :ma,
                       late = EXISTS (SELECT 1 FROM charges.accrual_days d
                                       WHERE d.business_date = u.business_date AND d.status = 'DONE')
                 WHERE business_date = :d AND credit_account_id = :a
                   AND status = 'CLAIMED' AND fencing_token = :t""",
                key(businessDate, creditAccountId).addValue("t", fencingToken)
                        .addValue("oc", t.ordinaryCharges()).addValue("oa", t.ordinaryAmount())
                        .addValue("mc", t.moratoriumCharges()).addValue("ma", t.moratoriumAmount())) == 1;
    }

    @Override
    public void fail(LocalDate businessDate, UUID creditAccountId, long fencingToken, String error) {
        jdbc.update("""
                UPDATE charges.accrual_units
                   SET status = 'FAILED', failed_at = now(), last_error = :e, lease_expires_at = NULL
                 WHERE business_date = :d AND credit_account_id = :a
                   AND status = 'CLAIMED' AND fencing_token = :t""",
                key(businessDate, creditAccountId).addValue("t", fencingToken).addValue("e", error));
    }

    @Override
    public int reap(LocalDate businessDate) {
        return jdbc.update("""
                UPDATE charges.accrual_units
                   SET status = 'PENDING', dispatched_at = NULL, lease_expires_at = NULL
                 WHERE business_date = :d AND status = 'CLAIMED' AND lease_expires_at < now()""",
                new MapSqlParameterSource("d", businessDate));
    }

    @Override
    public int requeueFailed(LocalDate businessDate, int maxAttempts, Duration backoff) {
        return jdbc.update("""
                UPDATE charges.accrual_units
                   SET status = 'PENDING', dispatched_at = NULL
                 WHERE business_date = :d AND status = 'FAILED' AND attempts < :max
                   AND failed_at <= now() - make_interval(secs => :backoff)""",
                new MapSqlParameterSource("d", businessDate).addValue("max", maxAttempts)
                        .addValue("backoff", backoff.toMillis() / 1000.0));
    }

    @Override
    public List<Trouble> quarantineExhausted(LocalDate businessDate, int maxAttempts) {
        return jdbc.query("""
                UPDATE charges.accrual_units
                   SET status = 'QUARANTINED', held_reason = last_error
                 WHERE business_date = :d AND status = 'FAILED' AND attempts >= :max
                RETURNING business_date, credit_account_id, status, attempts, last_error AS reason""",
                new MapSqlParameterSource("d", businessDate).addValue("max", maxAttempts), JdbcAccrualUnitStore::trouble);
    }

    @Override
    public int releaseHeld() {
        return jdbc.update("""
                UPDATE charges.accrual_units u
                   SET status = 'PENDING', held_reason = NULL, dispatched_at = NULL
                 WHERE u.status = 'HELD'
                   AND NOT EXISTS (SELECT 1 FROM charges.accrual_units p
                                    WHERE p.credit_account_id = u.credit_account_id
                                      AND p.business_date < u.business_date AND p.status <> 'DONE')""",
                new MapSqlParameterSource());
    }

    @Override
    public List<UUID> dispatchable(LocalDate businessDate, Instant staleBefore, UUID after, int limit) {
        return jdbc.queryForList("""
                SELECT credit_account_id FROM charges.accrual_units
                 WHERE business_date = :d AND status = 'PENDING'
                   AND (dispatched_at IS NULL OR dispatched_at < :stale)
                   AND credit_account_id > :after
                 ORDER BY credit_account_id
                 LIMIT :limit""",
                new MapSqlParameterSource("d", businessDate).addValue("stale", Timestamp.from(staleBefore))
                        .addValue("after", after == null ? new UUID(0L, 0L) : after)
                        .addValue("limit", Math.max(limit, 1)), UUID.class);
    }

    @Override
    public void markDispatched(LocalDate businessDate, Collection<UUID> creditAccountIds, Instant at) {
        if (creditAccountIds.isEmpty()) return;
        jdbc.update("""
                UPDATE charges.accrual_units SET dispatched_at = :at
                 WHERE business_date = :d AND credit_account_id IN (:ids) AND status = 'PENDING'""",
                new MapSqlParameterSource("d", businessDate).addValue("ids", creditAccountIds)
                        .addValue("at", Timestamp.from(at)));
    }

    @Override
    public Counts counts(LocalDate businessDate) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'PENDING')     AS pending,
                       count(*) FILTER (WHERE status = 'CLAIMED')     AS claimed,
                       count(*) FILTER (WHERE status = 'DONE')        AS done,
                       count(*) FILTER (WHERE status = 'FAILED')      AS failed,
                       count(*) FILTER (WHERE status = 'QUARANTINED') AS quarantined,
                       count(*) FILTER (WHERE status = 'HELD')        AS held
                  FROM charges.accrual_units WHERE business_date = :d""",
                new MapSqlParameterSource("d", businessDate),
                (rs, i) -> new Counts(rs.getInt("pending"), rs.getInt("claimed"), rs.getInt("done"),
                        rs.getInt("failed"), rs.getInt("quarantined"), rs.getInt("held")));
    }

    @Override
    public List<UUID> heldAccounts(LocalDate businessDate) {
        return jdbc.queryForList("""
                SELECT credit_account_id FROM charges.accrual_units
                 WHERE business_date = :d AND NOT (status = 'DONE' AND NOT late)
                 ORDER BY credit_account_id""", new MapSqlParameterSource("d", businessDate), UUID.class);
    }

    @Override
    public List<LocalDate> openDays() {
        return jdbc.queryForList("""
                SELECT DISTINCT business_date FROM charges.accrual_units
                 WHERE status IN ('PENDING', 'CLAIMED', 'FAILED') ORDER BY business_date""",
                new MapSqlParameterSource(), LocalDate.class);
    }

    @Override
    public List<LateUnit> lateUnannounced(int limit) {
        return jdbc.query("""
                SELECT business_date, credit_account_id, ordinary_charges, ordinary_amount,
                       moratorium_charges, moratorium_amount
                  FROM charges.accrual_units
                 WHERE late AND status = 'DONE' AND late_announced_at IS NULL
                 ORDER BY business_date, credit_account_id
                 LIMIT :limit""", new MapSqlParameterSource("limit", Math.max(limit, 1)),
                (rs, i) -> new LateUnit(rs.getObject("business_date", LocalDate.class),
                        rs.getObject("credit_account_id", UUID.class),
                        new AccountTotals(rs.getInt("ordinary_charges"), nz(rs.getBigDecimal("ordinary_amount")),
                                rs.getInt("moratorium_charges"), nz(rs.getBigDecimal("moratorium_amount")))));
    }

    @Override
    public void lateAnnounced(LocalDate businessDate, UUID creditAccountId) {
        jdbc.update("""
                UPDATE charges.accrual_units SET late_announced_at = now()
                 WHERE business_date = :d AND credit_account_id = :a""", key(businessDate, creditAccountId));
    }

    @Override
    public int retryQuarantined(LocalDate businessDate, UUID creditAccountIdOrNull) {
        return jdbc.update("""
                UPDATE charges.accrual_units
                   SET status = 'PENDING', attempts = 0, dispatched_at = NULL, failed_at = NULL
                 WHERE business_date = :d AND status = 'QUARANTINED'
                   AND (CAST(:a AS uuid) IS NULL OR credit_account_id = CAST(:a AS uuid))""",
                key(businessDate, creditAccountIdOrNull));
    }

    @Override
    public List<Trouble> inTrouble(int limit) {
        return jdbc.query("""
                SELECT business_date, credit_account_id, status, attempts,
                       coalesce(held_reason, last_error) AS reason
                  FROM charges.accrual_units
                 WHERE status IN ('QUARANTINED', 'HELD')
                 ORDER BY business_date, credit_account_id
                 LIMIT :limit""", new MapSqlParameterSource("limit", Math.max(limit, 1)), JdbcAccrualUnitStore::trouble);
    }

    @Override
    public TroubleCounts troubleCounts() {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'QUARANTINED') AS q,
                       count(*) FILTER (WHERE status = 'HELD')        AS h
                  FROM charges.accrual_units WHERE status IN ('QUARANTINED', 'HELD')""",
                new MapSqlParameterSource(), (rs, i) -> new TroubleCounts(rs.getInt("q"), rs.getInt("h")));
    }

    private static MapSqlParameterSource key(LocalDate businessDate, UUID creditAccountId) {
        return new MapSqlParameterSource("d", businessDate).addValue("a", creditAccountId);
    }

    private static Trouble trouble(ResultSet rs, int i) throws SQLException {
        return new Trouble(rs.getObject("business_date", LocalDate.class), rs.getObject("credit_account_id", UUID.class),
                rs.getString("status"), rs.getInt("attempts"), rs.getString("reason"));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
