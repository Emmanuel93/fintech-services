package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.PeriodCloseRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
class JdbcPeriodCloseRepository implements PeriodCloseRepository {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPeriodCloseRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String period, String outcome, Instant runAt, String checksJson, String trialBalanceJson,
                     String trialBalanceByProductJson) {
        jdbc.update("""
                INSERT INTO accounting.period_closes (period, outcome, run_at, checks, trial_balance,
                                                      trial_balance_by_product)
                VALUES (:period, :outcome, :runAt, CAST(:checks AS jsonb), CAST(:trialBalance AS jsonb),
                        CAST(:byProduct AS jsonb))
                ON CONFLICT (period) DO UPDATE
                   SET outcome = EXCLUDED.outcome, run_at = EXCLUDED.run_at, checks = EXCLUDED.checks,
                       trial_balance = EXCLUDED.trial_balance,
                       trial_balance_by_product = EXCLUDED.trial_balance_by_product,
                       attempts = accounting.period_closes.attempts + 1""",
                new MapSqlParameterSource("period", period).addValue("outcome", outcome)
                        .addValue("runAt", Timestamp.from(runAt)).addValue("checks", checksJson)
                        .addValue("trialBalance", trialBalanceJson).addValue("byProduct", trialBalanceByProductJson));
    }

    @Override
    public Optional<StoredClose> find(String period) {
        return jdbc.query("""
                SELECT period, outcome, run_at, attempts, checks::text AS checks, trial_balance::text AS trial_balance
                  FROM accounting.period_closes WHERE period = :period""",
                new MapSqlParameterSource("period", period),
                (rs, i) -> new StoredClose(rs.getString("period"), rs.getString("outcome"),
                        rs.getTimestamp("run_at").toInstant(), rs.getInt("attempts"), rs.getString("checks"),
                        rs.getString("trial_balance"))).stream().findFirst();
    }
}
