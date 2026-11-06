package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.PortfolioSelfCheckQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
class JdbcPortfolioSelfCheckQueries implements PortfolioSelfCheckQueries {

    /** The debt of an account as its balances say it. */
    private static final String DEBT = "coalesce(a.principal_balance, 0) + coalesce(a.accrued_interest_balance, 0)"
            + " + coalesce(a.iva_balance, 0) + coalesce(a.penalty_balance, 0)";

    private final JdbcTemplate jdbc;

    JdbcPortfolioSelfCheckQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Book book() {
        return jdbc.queryForObject("SELECT count(*), coalesce(sum(" + DEBT + "), 0) FROM credit_portfolio.credit_accounts a",
                (rs, i) -> new Book(rs.getInt(1), rs.getBigDecimal(2)));
    }

    @Override
    public List<LedgerGap> ledgerGaps() {
        // The same rule the day photo applies (ledger_gap), on the balances of now.
        return jdbc.query("""
                WITH e AS (
                    SELECT credit_account_id, sum(delta_amount) AS total
                      FROM credit_portfolio.balance_events GROUP BY credit_account_id)
                SELECT a.credit_account_id, a.contract_number, %s AS debt, coalesce(e.total, 0) AS ledger
                  FROM credit_portfolio.credit_accounts a
                  LEFT JOIN e ON e.credit_account_id = a.credit_account_id
                 WHERE abs(%s - coalesce(e.total, 0)) > 0.005
                 ORDER BY a.contract_number""".formatted(DEBT, DEBT),
                (rs, i) -> new LedgerGap(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4)));
    }

    @Override
    public int snapshots(String period) {
        Integer n = period == null || period.isBlank()
                ? jdbc.queryForObject("SELECT count(*) FROM credit_portfolio.account_day_snapshots", Integer.class)
                : jdbc.queryForObject("SELECT count(*) FROM credit_portfolio.account_day_snapshots "
                        + "WHERE to_char(business_date, 'YYYYMM') = ?", Integer.class, period);
        return n == null ? 0 : n;
    }

    @Override
    public List<SnapshotGap> snapshotGaps(String period) {
        String sql = "SELECT business_date, credit_account_id, ledger_gap FROM credit_portfolio.account_day_snapshots "
                + "WHERE abs(coalesce(ledger_gap, 0)) > 0.005";
        if (period == null || period.isBlank()) {
            return jdbc.query(sql + " ORDER BY business_date", this::snapshotGap);
        }
        return jdbc.query(sql + " AND to_char(business_date, 'YYYYMM') = ? ORDER BY business_date", this::snapshotGap,
                period);
    }

    @Override
    public int dayCloses(LocalDate before) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM credit_portfolio.day_closes WHERE business_date < ?",
                Integer.class, Date.valueOf(before));
        return n == null ? 0 : n;
    }

    @Override
    public List<OpenClose> openClosesBefore(LocalDate day) {
        return jdbc.query("""
                SELECT business_date, status, coalesce(errors, 0), last_error FROM credit_portfolio.day_closes
                 WHERE business_date < ? AND (status <> 'DONE' OR coalesce(errors, 0) > 0)
                 ORDER BY business_date""",
                (rs, i) -> new OpenClose(rs.getDate(1).toLocalDate(), rs.getString(2), rs.getInt(3), rs.getString(4)),
                Date.valueOf(day));
    }

    @Override
    public List<NegativeBalance> negativeBalances() {
        return jdbc.query("""
                SELECT credit_account_id, contract_number, principal_balance, accrued_interest_balance, iva_balance,
                       penalty_balance
                  FROM credit_portfolio.credit_accounts
                 WHERE coalesce(principal_balance, 0) < -0.005 OR coalesce(accrued_interest_balance, 0) < -0.005
                    OR coalesce(iva_balance, 0) < -0.005 OR coalesce(penalty_balance, 0) < -0.005
                 ORDER BY contract_number""",
                (rs, i) -> new NegativeBalance(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6)));
    }

    @Override
    public int cancelledAccounts() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM credit_portfolio.credit_accounts WHERE status = 'CANCELLED'", Integer.class);
        return n == null ? 0 : n;
    }

    @Override
    public List<UnfinishedCancellation> unfinishedCancellations() {
        return jdbc.query("""
                SELECT a.credit_account_id, a.contract_number,
                       coalesce(a.principal_balance, 0) + coalesce(a.accrued_interest_balance, 0)
                           + coalesce(a.iva_balance, 0) + coalesce(a.penalty_balance, 0) AS debt,
                       (SELECT count(*) FROM credit_portfolio.installments i
                         WHERE i.schedule_id = a.credit_account_id
                           AND i.status IN ('PENDING', 'OVERDUE', 'PARTIAL')) AS live
                  FROM credit_portfolio.credit_accounts a
                 WHERE a.status = 'CANCELLED'
                   AND (abs(coalesce(a.principal_balance, 0)) + abs(coalesce(a.accrued_interest_balance, 0))
                        + abs(coalesce(a.iva_balance, 0)) + abs(coalesce(a.penalty_balance, 0)) > 0.005
                        OR EXISTS (SELECT 1 FROM credit_portfolio.installments i
                                    WHERE i.schedule_id = a.credit_account_id
                                      AND i.status IN ('PENDING', 'OVERDUE', 'PARTIAL')))
                 ORDER BY a.contract_number""",
                (rs, i) -> new UnfinishedCancellation(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getBigDecimal(3), rs.getInt(4)));
    }

    @Override
    public List<PendingDisposition> dispositionsInFlightBefore(Instant moment) {
        return jdbc.query("""
                SELECT disposition_id, credit_account_id, amount, created_at FROM credit_portfolio.dispositions
                 WHERE status IN ('PENDING', 'PROCESSING') AND created_at < ?
                 ORDER BY created_at""",
                (rs, i) -> new PendingDisposition(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getBigDecimal(3), rs.getTimestamp(4).toInstant()),
                Timestamp.from(moment));
    }

    private SnapshotGap snapshotGap(ResultSet rs, int i) throws SQLException {
        return new SnapshotGap(rs.getDate(1).toLocalDate(), rs.getObject(2, UUID.class), rs.getBigDecimal(3));
    }
}
