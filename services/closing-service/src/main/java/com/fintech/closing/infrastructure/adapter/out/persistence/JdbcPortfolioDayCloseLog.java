package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.application.port.out.PortfolioDayCloseLog;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcPortfolioDayCloseLog implements PortfolioDayCloseLog {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPortfolioDayCloseLog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(LocalDate businessDate, LocalDate delinquencyAsOf, int accounts, int errors,
                       List<UUID> heldAccounts) {
        jdbc.update("""
                INSERT INTO closing.portfolio_day_closes (business_date, delinquency_as_of, accounts, errors, held_accounts)
                VALUES (:d, :asOf, :a, :e, CAST(:held AS uuid[]))
                ON CONFLICT (business_date) DO NOTHING""",
                new MapSqlParameterSource("d", businessDate).addValue("asOf", delinquencyAsOf)
                        .addValue("a", accounts).addValue("e", errors).addValue("held", uuidArray(heldAccounts)));
    }

    @Override
    public List<UUID> heldAccounts(LocalDate businessDate) {
        return jdbc.queryForList("""
                SELECT h.cuenta FROM closing.portfolio_day_closes p, unnest(p.held_accounts) AS h(cuenta)
                 WHERE p.business_date = :d
                   AND NOT EXISTS (SELECT 1 FROM closing.held_releases r
                                    WHERE r.business_date = p.business_date AND r.credit_account_id = h.cuenta)
                 ORDER BY h.cuenta""", new MapSqlParameterSource("d", businessDate), UUID.class);
    }

    @Override
    public void recordRelease(LocalDate businessDate, UUID creditAccountId) {
        jdbc.update("""
                INSERT INTO closing.held_releases (business_date, credit_account_id) VALUES (:d, :a)
                ON CONFLICT DO NOTHING""",
                new MapSqlParameterSource("d", businessDate).addValue("a", creditAccountId));
    }

    private static String uuidArray(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return "{}";
        return ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    @Override
    public boolean closed(LocalDate businessDate) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM closing.portfolio_day_closes WHERE business_date = :d",
                new MapSqlParameterSource("d", businessDate), Integer.class);
        return n != null && n > 0;
    }

    @Override
    public List<LocalDate> pendingCutoffs() {
        return jdbc.queryForList("""
                SELECT business_date FROM closing.portfolio_day_closes
                 WHERE cutoff_planned_at IS NULL ORDER BY business_date""",
                new MapSqlParameterSource(), LocalDate.class);
    }

    @Override
    public void waiting(LocalDate businessDate, String reason) {
        jdbc.update("UPDATE closing.portfolio_day_closes SET waiting_for = :r WHERE business_date = :d",
                new MapSqlParameterSource("d", businessDate).addValue("r", reason));
    }

    @Override
    public void planned(LocalDate businessDate) {
        jdbc.update("""
                UPDATE closing.portfolio_day_closes SET cutoff_planned_at = now(), waiting_for = NULL
                 WHERE business_date = :d AND cutoff_planned_at IS NULL""",
                new MapSqlParameterSource("d", businessDate));
    }

    @Override
    public List<LocalDate> completedNotAnnounced() {
        return jdbc.queryForList("""
                SELECT p.business_date FROM closing.portfolio_day_closes p
                  JOIN closing.close_runs r ON r.business_date = p.business_date AND r.phase = 'CUTOFF'
                 WHERE p.cutoff_planned_at IS NOT NULL AND p.completion_announced_at IS NULL
                   AND r.status IN ('SEALED', 'FAILED')
                 ORDER BY p.business_date""", new MapSqlParameterSource(), LocalDate.class);
    }

    @Override
    public void completionAnnounced(LocalDate businessDate) {
        jdbc.update("UPDATE closing.portfolio_day_closes SET completion_announced_at = now() WHERE business_date = :d",
                new MapSqlParameterSource("d", businessDate));
    }

    @Override
    public Optional<String> waitingFor(LocalDate businessDate) {
        return jdbc.queryForList("SELECT waiting_for FROM closing.portfolio_day_closes WHERE business_date = :d",
                new MapSqlParameterSource("d", businessDate), String.class).stream().filter(w -> w != null).findFirst();
    }
}
