package com.fintech.charges.infrastructure.adapter.out.persistence;

import com.fintech.charges.application.port.out.DaySnapshotStore;
import com.fintech.charges.application.port.out.PortfolioDaySnapshots.AccountSnapshot;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcDaySnapshotStore implements DaySnapshotStore {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcDaySnapshotStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int store(LocalDate businessDate, Collection<AccountSnapshot> accounts) {
        if (accounts.isEmpty()) return 0;
        SqlParameterSource[] batch = accounts.stream()
                .map(a -> new MapSqlParameterSource("d", businessDate)
                        .addValue("a", a.creditAccountId())
                        .addValue("p", a.principal())
                        .addValue("ib", a.interestBasis())
                        .addValue("si", a.scheduledInterest())
                        .addValue("st", a.scheduledTax())
                        .addValue("o", a.overduePrincipal())
                        .addValue("due", a.oldestDueDate())
                        .addValue("dpd", a.daysDelinquent())
                        .addValue("v", a.balanceVersion())
                        .addValue("at", Timestamp.from(a.takenAt())))
                .toArray(SqlParameterSource[]::new);
        int[] rows = jdbc.batchUpdate("""
                INSERT INTO charges.account_day_snapshots (business_date, credit_account_id, principal, overdue_principal,
                    oldest_due_date, days_delinquent, balance_version, taken_at, interest_bearing_principal, scheduled_interest,
                    scheduled_tax)
                VALUES (:d, :a, :p, :o, :due, :dpd, :v, :at, :ib, :si, :st)
                ON CONFLICT (business_date, credit_account_id) DO NOTHING""", batch);
        return Arrays.stream(rows).sum();
    }

    @Override
    public int count(LocalDate businessDate) {
        return jdbc.queryForObject("SELECT count(*) FROM charges.account_day_snapshots WHERE business_date = :d",
                new MapSqlParameterSource("d", businessDate), Integer.class);
    }

    @Override
    public Optional<AccountSnapshot> find(LocalDate businessDate, UUID creditAccountId) {
        return jdbc.query("""
                SELECT * FROM charges.account_day_snapshots WHERE business_date = :d AND credit_account_id = :a""",
                new MapSqlParameterSource("d", businessDate).addValue("a", creditAccountId),
                (rs, i) -> new AccountSnapshot(rs.getObject("credit_account_id", UUID.class), rs.getBigDecimal("principal"),
                        rs.getBigDecimal("overdue_principal"), rs.getObject("oldest_due_date", LocalDate.class),
                        rs.getInt("days_delinquent"), rs.getLong("balance_version"), rs.getTimestamp("taken_at").toInstant(),
                        rs.getBigDecimal("interest_bearing_principal"), rs.getBigDecimal("scheduled_interest"),
                        rs.getBigDecimal("scheduled_tax")))
                .stream().findFirst();
    }
}
