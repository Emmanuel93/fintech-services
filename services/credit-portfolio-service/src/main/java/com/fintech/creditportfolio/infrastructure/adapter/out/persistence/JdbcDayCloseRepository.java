package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.DayCloseRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcDayCloseRepository implements DayCloseRepository {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcDayCloseRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Claim claim(LocalDate businessDate, LocalDate delinquencyAsOf, String triggeredBy, Duration staleAfter) {
        var p = new MapSqlParameterSource()
                .addValue("d", businessDate)
                .addValue("asOf", delinquencyAsOf)
                .addValue("by", triggeredBy)
                .addValue("stale", staleAfter.toSeconds());
        int nuevo = jdbc.update("""
                INSERT INTO credit_portfolio.day_closes (business_date, delinquency_as_of, status, triggered_by, attempts)
                VALUES (:d, :asOf, 'RUNNING', :by, 1)
                ON CONFLICT (business_date) DO NOTHING""", p);
        if (nuevo == 1) return Claim.CLAIMED;

        int retomado = jdbc.update("""
                UPDATE credit_portfolio.day_closes
                   SET status = 'RUNNING', triggered_by = :by, started_at = now(), completed_at = NULL,
                       last_error = NULL, waiting_for = NULL, delinquency_as_of = :asOf, attempts = attempts + 1
                 WHERE business_date = :d
                   AND (status IN ('WAITING', 'FAILED')
                        OR (status = 'RUNNING' AND started_at < now() - make_interval(secs => :stale)))""", p);
        if (retomado == 1) return Claim.CLAIMED;

        return find(businessDate).filter(DayClose::done).isPresent() ? Claim.ALREADY_DONE : Claim.IN_PROGRESS;
    }

    @Override
    public void waiting(LocalDate businessDate, LocalDate delinquencyAsOf, String triggeredBy, String reason) {
        jdbc.update("""
                INSERT INTO credit_portfolio.day_closes (business_date, delinquency_as_of, status, triggered_by, waiting_for)
                VALUES (:d, :asOf, 'WAITING', :by, :r)
                ON CONFLICT (business_date) DO UPDATE
                   SET status = 'WAITING', triggered_by = :by, waiting_for = :r
                 WHERE credit_portfolio.day_closes.status IN ('WAITING', 'FAILED')""",
                new MapSqlParameterSource("d", businessDate).addValue("asOf", delinquencyAsOf)
                        .addValue("by", triggeredBy).addValue("r", reason));
    }

    @Override
    public void complete(LocalDate businessDate, int accounts, int errors) {
        jdbc.update("""
                UPDATE credit_portfolio.day_closes
                   SET status = 'DONE', accounts = :a, errors = :e, completed_at = now(), waiting_for = NULL
                 WHERE business_date = :d""",
                new MapSqlParameterSource("d", businessDate).addValue("a", accounts).addValue("e", errors));
    }

    @Override
    public void fail(LocalDate businessDate, String error) {
        jdbc.update("""
                UPDATE credit_portfolio.day_closes
                   SET status = 'FAILED', last_error = :err, completed_at = now()
                 WHERE business_date = :d""",
                new MapSqlParameterSource("d", businessDate).addValue("err", error));
    }

    @Override
    public Optional<DayClose> find(LocalDate businessDate) {
        return jdbc.query("""
                SELECT business_date, delinquency_as_of, status, triggered_by, accounts, errors, last_error,
                       waiting_for, attempts, started_at, completed_at
                  FROM credit_portfolio.day_closes WHERE business_date = :d""",
                new MapSqlParameterSource("d", businessDate),
                (rs, i) -> new DayClose(
                        rs.getObject("business_date", LocalDate.class),
                        rs.getObject("delinquency_as_of", LocalDate.class),
                        rs.getString("status"),
                        rs.getString("triggered_by"),
                        (Integer) rs.getObject("accounts"),
                        (Integer) rs.getObject("errors"),
                        rs.getString("last_error"),
                        rs.getString("waiting_for"),
                        rs.getInt("attempts"),
                        instant(rs.getTimestamp("started_at")),
                        instant(rs.getTimestamp("completed_at"))))
                .stream().findFirst();
    }

    @Override
    public boolean anyBefore(LocalDate businessDate) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM credit_portfolio.day_closes WHERE business_date < :d",
                new MapSqlParameterSource("d", businessDate), Integer.class);
        return n != null && n > 0;
    }

    @Override
    public void recordChargesAccrued(ChargesDay day) {
        ChargesTotals t = day.totals();
        jdbc.update("""
                INSERT INTO credit_portfolio.charges_day_accruals
                       (business_date, ordinary_charges, ordinary_amount, moratorium_charges, moratorium_amount, held_accounts)
                VALUES (:d, :oc, :oa, :mc, :ma, CAST(:held AS uuid[]))
                ON CONFLICT (business_date) DO UPDATE
                   SET ordinary_charges = EXCLUDED.ordinary_charges, ordinary_amount = EXCLUDED.ordinary_amount,
                       moratorium_charges = EXCLUDED.moratorium_charges, moratorium_amount = EXCLUDED.moratorium_amount,
                       held_accounts = EXCLUDED.held_accounts""",
                new MapSqlParameterSource("d", day.businessDate())
                        .addValue("oc", t.ordinaryCharges()).addValue("oa", t.ordinaryAmount())
                        .addValue("mc", t.moratoriumCharges()).addValue("ma", t.moratoriumAmount())
                        .addValue("held", uuidArray(day.heldAccounts())));
    }

    @Override
    public Optional<ChargesDay> chargesDay(LocalDate businessDate) {
        return jdbc.query("SELECT * FROM credit_portfolio.charges_day_accruals WHERE business_date = :d",
                new MapSqlParameterSource("d", businessDate),
                (rs, i) -> new ChargesDay(rs.getObject("business_date", LocalDate.class),
                        new ChargesTotals(rs.getInt("ordinary_charges"), rs.getBigDecimal("ordinary_amount"),
                                rs.getInt("moratorium_charges"), rs.getBigDecimal("moratorium_amount")),
                        instant(rs.getTimestamp("received_at")), uuids(rs.getArray("held_accounts"))))
                .stream().findFirst();
    }

    @Override
    public List<LocalDate> announcedNotClosed() {
        return jdbc.queryForList("""
                SELECT c.business_date FROM credit_portfolio.charges_day_accruals c
                  LEFT JOIN credit_portfolio.day_closes d ON d.business_date = c.business_date
                 WHERE d.status IS DISTINCT FROM 'DONE'
                 ORDER BY c.business_date""", new MapSqlParameterSource(), LocalDate.class);
    }

    @Override
    public ChargesTotals appliedInterest(LocalDate businessDate, Collection<UUID> outsideBatch) {
        // Las cuentas que cargos dejó fuera del lote no cuentan: si devengan tarde, sus cargos llevan el
        // mismo día de lote y se cuadran aparte, cuenta por cuenta.
        var p = new MapSqlParameterSource("d", businessDate);
        String fuera = "";
        if (outsideBatch != null && !outsideBatch.isEmpty()) {
            fuera = " AND credit_account_id NOT IN (:fuera)";
            p.addValue("fuera", outsideBatch);
        }
        return interest(fuera, p);
    }

    @Override
    public ChargesTotals accountInterest(LocalDate businessDate, UUID creditAccountId) {
        return interest(" AND credit_account_id = :a",
                new MapSqlParameterSource("d", businessDate).addValue("a", creditAccountId));
    }

    /** El lote del cierre de D: aplicados (balance_events) más rechazados (charge_rejections), los dos llegaron. */
    private ChargesTotals interest(String filtro, MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                WITH llegaron AS (
                    SELECT substring(event_type FROM 8) AS tipo, delta_amount AS importe
                      FROM credit_portfolio.balance_events
                     WHERE day_close_date = :d AND event_type IN ('CHARGE_ORDINARY_INTEREST', 'CHARGE_MORATORIUM_INTEREST')
                    """ + filtro + """

                    UNION ALL
                    SELECT charge_type, amount
                      FROM credit_portfolio.charge_rejections
                     WHERE day_close_date = :d AND charge_type IN ('ORDINARY_INTEREST', 'MORATORIUM_INTEREST')
                    """ + filtro + """
                )
                SELECT count(*) FILTER (WHERE tipo = 'ORDINARY_INTEREST')                     AS oc,
                       coalesce(sum(importe) FILTER (WHERE tipo = 'ORDINARY_INTEREST'), 0)    AS oa,
                       count(*) FILTER (WHERE tipo = 'MORATORIUM_INTEREST')                   AS mc,
                       coalesce(sum(importe) FILTER (WHERE tipo = 'MORATORIUM_INTEREST'), 0)  AS ma
                  FROM llegaron""", p,
                (rs, i) -> new ChargesTotals(rs.getInt("oc"), rs.getBigDecimal("oa"),
                        rs.getInt("mc"), rs.getBigDecimal("ma")));
    }

    static String uuidArray(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return "{}";
        return ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    static List<UUID> uuids(java.sql.Array array) throws java.sql.SQLException {
        if (array == null) return List.of();
        Object[] valores = (Object[]) array.getArray();
        List<UUID> out = new java.util.ArrayList<>(valores.length);
        for (Object v : valores) out.add(v instanceof UUID u ? u : UUID.fromString(v.toString()));
        return List.copyOf(out);
    }

    private static java.time.Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
