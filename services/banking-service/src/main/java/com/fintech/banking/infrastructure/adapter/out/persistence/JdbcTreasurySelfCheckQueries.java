package com.fintech.banking.infrastructure.adapter.out.persistence;

import com.fintech.banking.application.port.out.TreasurySelfCheckQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
class JdbcTreasurySelfCheckQueries implements TreasurySelfCheckQueries {

    private final JdbcTemplate jdbc;

    JdbcTreasurySelfCheckQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FundingFigures> funding() {
        return jdbc.query("""
                SELECT s.funding_source_id, s.kind, s.counterparty_name, s.contributed, s.principal, s.interest_payable,
                       coalesce(sum(m.amount) FILTER (WHERE m.movement_type = 'CONTRIBUTION'), 0) AS contributions,
                       coalesce(sum(m.amount) FILTER (WHERE m.movement_type = 'DRAWDOWN'), 0)
                     - coalesce(sum(m.amount) FILTER (WHERE m.movement_type = 'PRINCIPAL_REPAYMENT'), 0) AS drawn,
                       coalesce(sum(m.amount) FILTER (WHERE m.movement_type = 'INTEREST_ACCRUAL'), 0)
                     - coalesce(sum(m.amount) FILTER (WHERE m.movement_type = 'INTEREST_PAYMENT'), 0) AS accrued
                  FROM banking.funding_sources s
                  LEFT JOIN banking.funding_movements m ON m.funding_source_id = s.funding_source_id
                 GROUP BY s.funding_source_id, s.kind, s.counterparty_name, s.contributed, s.principal, s.interest_payable
                 ORDER BY s.kind, s.counterparty_name""",
                (rs, i) -> new FundingFigures(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                        rs.getBigDecimal(8), rs.getBigDecimal(9)));
    }

    @Override
    public List<Accrual> accruals() {
        // Actual days over 360, the window inclusive: the rule FundingSource.accrueThrough books with.
        return jdbc.query("""
                SELECT m.funding_source_id, m.accrued_from, m.accrued_through, m.amount,
                       round(m.principal_after * s.annual_rate * (m.accrued_through - m.accrued_from + 1) / 360, 2),
                       lag(m.accrued_through) OVER (PARTITION BY m.funding_source_id ORDER BY m.accrued_from)
                  FROM banking.funding_movements m
                  JOIN banking.funding_sources s ON s.funding_source_id = m.funding_source_id
                 WHERE m.movement_type = 'INTEREST_ACCRUAL'
                 ORDER BY m.funding_source_id, m.accrued_from""",
                (rs, i) -> new Accrual(rs.getObject(1, UUID.class), localDate(rs.getDate(2)), localDate(rs.getDate(3)),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), localDate(rs.getDate(6))));
    }

    @Override
    public List<PayoutMismatch> payoutMismatches() {
        // The cash book holds an order from its settlement on. A payout the receiving bank returned before it settled
        // never left the account: it is in neither side (D63). One returned after settling did leave, and stays here.
        return jdbc.query("""
                WITH caja AS (
                    SELECT tracking_key, sum(amount) AS amount FROM banking.internal_movements
                     WHERE movement_type = 'STP_ORDER' AND tracking_key IS NOT NULL GROUP BY tracking_key),
                     traza AS (
                    SELECT tracking_key, sum(amount) AS amount FROM banking.movement_trace
                     WHERE (status IN ('SETTLED', 'RECONCILED') OR (status = 'RETURNED' AND settled_at IS NOT NULL))
                       AND tracking_key IS NOT NULL
                     GROUP BY tracking_key)
                SELECT coalesce(c.tracking_key, t.tracking_key), c.amount, t.amount
                  FROM caja c FULL OUTER JOIN traza t ON t.tracking_key = c.tracking_key
                 WHERE c.tracking_key IS NULL OR t.tracking_key IS NULL OR abs(c.amount - t.amount) > 0.005
                 ORDER BY 1""",
                (rs, i) -> new PayoutMismatch(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)));
    }

    @Override
    public int settledOrders() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM banking.internal_movements WHERE movement_type = 'STP_ORDER'", Integer.class);
        return n == null ? 0 : n;
    }

    @Override
    public List<CashDay> negativeDays() {
        return jdbc.query("""
                WITH d AS (
                    SELECT business_date, sum(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) AS net
                      FROM banking.internal_movements GROUP BY business_date),
                     c AS (SELECT business_date, sum(net) OVER (ORDER BY business_date) AS balance FROM d)
                SELECT business_date, balance FROM c WHERE balance < -0.005 ORDER BY business_date""",
                (rs, i) -> new CashDay(localDate(rs.getDate(1)), rs.getBigDecimal(2)));
    }

    @Override
    public int cashDays() {
        Integer n = jdbc.queryForObject("SELECT count(DISTINCT business_date) FROM banking.internal_movements",
                Integer.class);
        return n == null ? 0 : n;
    }

    @Override
    public List<StaleTrace> tracesStoppedBefore(LocalDate day) {
        return jdbc.query("""
                SELECT payout_id, status, coalesce(business_date, created_at::date), amount FROM banking.movement_trace
                 WHERE status IN ('REQUESTED', 'ROUTED', 'DISPATCHED')
                   AND coalesce(business_date, created_at::date) < ?
                 ORDER BY 3""",
                (rs, i) -> new StaleTrace(rs.getObject(1, UUID.class), rs.getString(2), localDate(rs.getDate(3)),
                        rs.getBigDecimal(4)),
                Date.valueOf(day));
    }

    private static LocalDate localDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
