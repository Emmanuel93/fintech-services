package com.fintech.charges.infrastructure.adapter.out.persistence;

import com.fintech.charges.application.port.out.AccrualDayRepository;
import org.springframework.jdbc.core.RowMapper;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcAccrualDayRepository implements AccrualDayRepository {

    /** Los intereses del lote: ordinario y moratorio (el IVA viaja detrás de cada uno, en la misma partición). */
    private static final String INTEREST = "charge_type IN ('ORDINARY_INTEREST', 'MORATORIUM_INTEREST')";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcAccrualDayRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Claim claim(LocalDate businessDate, String triggeredBy, Duration staleAfter) {
        var p = new MapSqlParameterSource()
                .addValue("d", businessDate)
                .addValue("by", triggeredBy)
                .addValue("stale", staleAfter.toSeconds());
        int nuevo = jdbc.update("""
                INSERT INTO charges.accrual_days (business_date, status, triggered_by, attempts, started_at)
                VALUES (:d, 'RUNNING', :by, 1, now())
                ON CONFLICT (business_date) DO NOTHING""", p);
        if (nuevo == 1) return Claim.CLAIMED;

        int retomado = jdbc.update("""
                UPDATE charges.accrual_days
                   SET status = 'RUNNING', triggered_by = :by, attempts = attempts + 1, started_at = now(),
                       completed_at = NULL, waiting_for = NULL
                 WHERE business_date = :d
                   AND (status IN ('WAITING', 'FAILED')
                        OR (status = 'RUNNING' AND started_at < now() - make_interval(secs => :stale)))""", p);
        if (retomado == 1) return Claim.CLAIMED;

        return find(businessDate).filter(AccrualDay::done).isPresent() ? Claim.ALREADY_DONE : Claim.IN_PROGRESS;
    }

    @Override
    public void waiting(LocalDate businessDate, String triggeredBy, String reason) {
        jdbc.update("""
                INSERT INTO charges.accrual_days (business_date, status, triggered_by, waiting_for)
                VALUES (:d, 'WAITING', :by, :r)
                ON CONFLICT (business_date) DO UPDATE
                   SET status = 'WAITING', triggered_by = :by, waiting_for = :r
                 WHERE charges.accrual_days.status IN ('WAITING', 'FAILED')""",
                new MapSqlParameterSource("d", businessDate).addValue("by", triggeredBy).addValue("r", reason));
    }

    @Override
    public void planned(LocalDate businessDate, int units) {
        jdbc.update("UPDATE charges.accrual_days SET planned_units = :n WHERE business_date = :d",
                new MapSqlParameterSource("d", businessDate).addValue("n", units));
    }

    @Override
    public boolean complete(LocalDate businessDate, Tallies t, ControlTotals c, int heldUnits) {
        return jdbc.update("""
                UPDATE charges.accrual_days
                   SET status = 'DONE', waiting_for = NULL, last_error = NULL, completed_at = now(),
                       ordinary_schedules = :os, ordinary_errors = :oe,
                       moratorium_schedules = :ms, moratorium_errors = :me,
                       ordinary_charges = :oc, ordinary_amount = :oa,
                       moratorium_charges = :mc, moratorium_amount = :ma,
                       held_units = :held
                 WHERE business_date = :d AND status = 'RUNNING'""",
                new MapSqlParameterSource("d", businessDate)
                        .addValue("os", t.ordinarySchedules()).addValue("oe", t.ordinaryErrors())
                        .addValue("ms", t.moratoriumSchedules()).addValue("me", t.moratoriumErrors())
                        .addValue("oc", c.ordinaryCharges()).addValue("oa", c.ordinaryAmount())
                        .addValue("mc", c.moratoriumCharges()).addValue("ma", c.moratoriumAmount())
                        .addValue("held", heldUnits)) == 1;
    }

    @Override
    public void note(LocalDate businessDate, String reason) {
        jdbc.update("UPDATE charges.accrual_days SET waiting_for = :r WHERE business_date = :d",
                new MapSqlParameterSource("d", businessDate).addValue("r", reason));
    }

    @Override
    public List<LocalDate> running() {
        return jdbc.queryForList(
                "SELECT business_date FROM charges.accrual_days WHERE status = 'RUNNING' ORDER BY business_date",
                new MapSqlParameterSource(), LocalDate.class);
    }

    @Override
    public void fail(LocalDate businessDate, String error) {
        jdbc.update("""
                UPDATE charges.accrual_days
                   SET status = 'FAILED', last_error = :err, completed_at = now()
                 WHERE business_date = :d""",
                new MapSqlParameterSource("d", businessDate).addValue("err", error));
    }

    @Override
    public void announced(LocalDate businessDate) {
        jdbc.update("UPDATE charges.accrual_days SET announced_at = now() WHERE business_date = :d",
                new MapSqlParameterSource("d", businessDate));
    }

    @Override
    public void republished(LocalDate businessDate) {
        jdbc.update("UPDATE charges.accrual_days SET republished_at = now() WHERE business_date = :d",
                new MapSqlParameterSource("d", businessDate));
    }

    @Override
    public Optional<AccrualDay> find(LocalDate businessDate) {
        return jdbc.query("SELECT * FROM charges.accrual_days WHERE business_date = :d",
                new MapSqlParameterSource("d", businessDate), DAY).stream().findFirst();
    }

    @Override
    public Optional<LocalDate> lastDone() {
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT max(business_date) FROM charges.accrual_days WHERE status = 'DONE'",
                new MapSqlParameterSource(), LocalDate.class));
    }

    @Override
    public boolean anyDoneBefore(LocalDate businessDate) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM charges.accrual_days WHERE status = 'DONE' AND business_date < :d)",
                new MapSqlParameterSource("d", businessDate), Boolean.class));
    }

    @Override
    public boolean anyScheduleBornBefore(Instant endOfDay) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM charges.accrual_schedules WHERE created_at < :fin)",
                new MapSqlParameterSource("fin", Timestamp.from(endOfDay)), Boolean.class));
    }

    @Override
    public List<AccrualDay> unconfirmed(LocalDate since) {
        return jdbc.query("""
                SELECT a.* FROM charges.accrual_days a
                 WHERE a.status = 'DONE' AND a.business_date >= :since
                   AND NOT EXISTS (SELECT 1 FROM charges.portfolio_day_closes p WHERE p.business_date = a.business_date)
                 ORDER BY a.business_date""", new MapSqlParameterSource("since", since), DAY);
    }

    @Override
    public void recordPortfolioClose(LocalDate businessDate, LocalDate delinquencyAsOf) {
        jdbc.update("""
                INSERT INTO charges.portfolio_day_closes (business_date, delinquency_as_of)
                VALUES (:d, :asOf) ON CONFLICT (business_date) DO NOTHING""",
                new MapSqlParameterSource("d", businessDate).addValue("asOf", delinquencyAsOf));
    }

    @Override
    public boolean portfolioClosed(LocalDate businessDate) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM charges.portfolio_day_closes WHERE business_date = :d",
                new MapSqlParameterSource("d", businessDate), Integer.class);
        return n != null && n > 0;
    }

    @Override
    public void recordCutoffCompleted(LocalDate businessDate, String status) {
        jdbc.update("""
                INSERT INTO charges.cutoff_completions (business_date, status) VALUES (:d, :s)
                ON CONFLICT (business_date) DO UPDATE SET status = EXCLUDED.status, received_at = now()""",
                new MapSqlParameterSource("d", businessDate).addValue("s", status));
    }

    @Override
    public boolean cutoffCompleted(LocalDate businessDate) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM charges.cutoff_completions WHERE business_date = :d",
                new MapSqlParameterSource("d", businessDate), Integer.class);
        return n != null && n > 0;
    }

    @Override
    public ControlTotals totalsFor(LocalDate businessDate) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE charge_type = 'ORDINARY_INTEREST')                    AS oc,
                       coalesce(sum(amount) FILTER (WHERE charge_type = 'ORDINARY_INTEREST'), 0)   AS oa,
                       count(*) FILTER (WHERE charge_type = 'MORATORIUM_INTEREST')                  AS mc,
                       coalesce(sum(amount) FILTER (WHERE charge_type = 'MORATORIUM_INTEREST'), 0) AS ma
                  FROM charges.charge_records
                 WHERE day_close_date = :d AND\s""" + INTEREST,
                new MapSqlParameterSource("d", businessDate),
                (rs, i) -> new ControlTotals(rs.getInt("oc"), rs.getBigDecimal("oa"),
                        rs.getInt("mc"), rs.getBigDecimal("ma")));
    }

    @Override
    public List<AccruedCharge> accruedCharges(LocalDate businessDate) {
        // En el orden en que se crearon: por cuenta, interés y su IVA, después moratorio y su IVA. Es el
        // orden en que cartera los recibió la primera vez (misma llave, misma partición).
        return jdbc.query("""
                SELECT c.charge_id, c.credit_account_id, c.charge_type, c.amount, c.accrual_date
                  FROM charges.charge_records c
                 WHERE c.day_close_date = :d
                 ORDER BY c.created_at, c.charge_id""",
                new MapSqlParameterSource("d", businessDate),
                (rs, i) -> new AccruedCharge(rs.getObject("charge_id", UUID.class),
                        rs.getObject("credit_account_id", UUID.class), rs.getString("charge_type"),
                        rs.getBigDecimal("amount"), rs.getObject("accrual_date", LocalDate.class)));
    }

    @Override
    public List<AccountCharge> accountCharges(LocalDate businessDate, UUID creditAccountId) {
        return jdbc.query("""
                SELECT charge_id, charge_type, status, basis, rate, amount, accrual_date, created_at
                  FROM charges.charge_records
                 WHERE credit_account_id = :a AND (day_close_date = :d OR accrual_date = :d)
                 ORDER BY created_at, charge_id""",
                new MapSqlParameterSource("d", businessDate).addValue("a", creditAccountId),
                (rs, i) -> new AccountCharge(rs.getObject("charge_id", UUID.class), rs.getString("charge_type"),
                        rs.getString("status"), rs.getBigDecimal("basis"), rs.getBigDecimal("rate"),
                        rs.getBigDecimal("amount"), rs.getObject("accrual_date", LocalDate.class),
                        rs.getTimestamp("created_at").toInstant()));
    }

    private static final RowMapper<AccrualDay> DAY = JdbcAccrualDayRepository::day;

    private static AccrualDay day(ResultSet rs, int i) throws SQLException {
        return new AccrualDay(
                rs.getObject("business_date", LocalDate.class),
                rs.getString("status"),
                rs.getString("triggered_by"),
                rs.getInt("attempts"),
                rs.getString("waiting_for"),
                new Tallies(rs.getInt("ordinary_schedules"), rs.getInt("ordinary_errors"),
                        rs.getInt("moratorium_schedules"), rs.getInt("moratorium_errors")),
                new ControlTotals(rs.getInt("ordinary_charges"), nz(rs.getBigDecimal("ordinary_amount")),
                        rs.getInt("moratorium_charges"), nz(rs.getBigDecimal("moratorium_amount"))),
                rs.getString("last_error"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("completed_at")),
                instant(rs.getTimestamp("announced_at")),
                instant(rs.getTimestamp("republished_at")),
                (Integer) rs.getObject("planned_units"),
                (Integer) rs.getObject("held_units"));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }
}
