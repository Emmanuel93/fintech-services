package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.application.port.out.BalanceMovementRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Repository
class JdbcBalanceMovementRepository implements BalanceMovementRepository {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcBalanceMovementRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID creditAccountId, long balanceVersion, LocalDate valueDate,
                       BigDecimal principalDelta, BigDecimal debtDelta) {
        jdbc.update("""
                INSERT INTO closing.balance_movements
                       (credit_account_id, balance_version, value_date, principal_delta, debt_delta)
                VALUES (:cuenta, :version, :fecha, :capital, :deuda)
                ON CONFLICT (credit_account_id, balance_version) DO NOTHING""",
                new MapSqlParameterSource("cuenta", creditAccountId).addValue("version", balanceVersion)
                        .addValue("fecha", valueDate).addValue("capital", principalDelta).addValue("deuda", debtDelta));
    }

    @Override
    public Later after(UUID creditAccountId, LocalDate date) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(principal_delta), 0) AS capital, coalesce(sum(debt_delta), 0) AS deuda
                  FROM closing.balance_movements
                 WHERE credit_account_id = :cuenta AND value_date > :fecha""",
                new MapSqlParameterSource("cuenta", creditAccountId).addValue("fecha", date),
                (rs, i) -> new Later(rs.getBigDecimal("capital"), rs.getBigDecimal("deuda")));
    }

    @Override
    public int pruneBefore(LocalDate date) {
        return jdbc.update("DELETE FROM closing.balance_movements WHERE value_date < :fecha",
                new MapSqlParameterSource("fecha", date));
    }
}
