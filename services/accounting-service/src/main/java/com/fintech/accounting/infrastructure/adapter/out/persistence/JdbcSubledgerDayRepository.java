package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.SubledgerDayRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
class JdbcSubledgerDayRepository implements SubledgerDayRepository {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcSubledgerDayRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void dayClosed(LocalDate day, int accounts, List<UUID> held) {
        jdbc.update("""
                INSERT INTO accounting.subledger_day_closes (business_date, accounts) VALUES (:day, :accounts)
                ON CONFLICT (business_date) DO UPDATE SET accounts = EXCLUDED.accounts""",
                new MapSqlParameterSource("day", day).addValue("accounts", accounts));
        for (UUID account : held == null ? List.<UUID>of() : held) {
            // A release that arrived before the close (both topics are consumed in parallel) stays released.
            jdbc.update("""
                    INSERT INTO accounting.subledger_held_accounts (business_date, credit_account_id)
                    VALUES (:day, :account) ON CONFLICT DO NOTHING""",
                    new MapSqlParameterSource("day", day).addValue("account", account));
        }
    }

    @Override
    public void accountReleased(LocalDate day, UUID creditAccountId) {
        jdbc.update("""
                INSERT INTO accounting.subledger_held_accounts (business_date, credit_account_id, released_at)
                VALUES (:day, :account, now())
                ON CONFLICT (business_date, credit_account_id)
                DO UPDATE SET released_at = COALESCE(accounting.subledger_held_accounts.released_at, now())""",
                new MapSqlParameterSource("day", day).addValue("account", creditAccountId));
    }
}
