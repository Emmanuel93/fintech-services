package com.fintech.commission.infrastructure.adapter.out.persistence;

import com.fintech.commission.application.port.out.CommissionSelfCheckQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcCommissionSelfCheckQueries implements CommissionSelfCheckQueries {

    /** Each lot with what its records add up to: the reversals netted into it count with their sign. */
    private static final String LOTS = """
            WITH r AS (
                SELECT liquidation_batch_id AS batch_id, sum(amount) AS subtotal, sum(iva_amount) AS iva, count(*) AS n
                  FROM commission.commission_records
                 WHERE liquidation_batch_id IS NOT NULL
                 GROUP BY liquidation_batch_id)
            SELECT b.batch_id, b.beneficiary_party_id, b.product_type, b.period, b.status, b.subtotal, b.iva_amount,
                   b.isr_withheld, b.iva_withheld, b.total_amount, b.net_amount, b.records_count,
                   coalesce(r.subtotal, 0) AS records_subtotal, coalesce(r.iva, 0) AS records_iva,
                   coalesce(r.n, 0) AS records_found, b.payout_attempts, b.failure_reason, b.created_at
              FROM commission.liquidation_batches b
              LEFT JOIN r ON r.batch_id = b.batch_id""";

    private final JdbcTemplate jdbc;

    JdbcCommissionSelfCheckQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<LotFigures> lots(String period) {
        if (period == null || period.isBlank()) {
            return jdbc.query(LOTS + " ORDER BY b.period, b.created_at", this::lot);
        }
        return jdbc.query(LOTS + " WHERE b.period = ? ORDER BY b.created_at", this::lot, period);
    }

    @Override
    public List<OverReversal> overReversals() {
        return jdbc.query("""
                SELECT o.commission_id, o.amount, o.iva_amount, sum(r.amount) AS reversed, sum(r.iva_amount) AS iva_reversed
                  FROM commission.commission_records o
                  JOIN commission.commission_records r ON r.reverses_commission_id = o.commission_id
                 GROUP BY o.commission_id, o.amount, o.iva_amount
                HAVING o.amount + sum(r.amount) < -0.005 OR o.iva_amount + sum(r.iva_amount) < -0.005""",
                (rs, i) -> new OverReversal(rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5)));
    }

    @Override
    public List<OpenGroup> openAsOf(String period, Instant runAt) {
        // The same records the run takes (ACCRUED, of a period up to the run's), as it saw them: accrued by then.
        return jdbc.query("""
                SELECT beneficiary_party_id, product_type, sum(amount) AS subtotal, count(*) AS records
                  FROM commission.commission_records
                 WHERE status = 'ACCRUED' AND period <= ? AND accrual_date <= ?
                 GROUP BY beneficiary_party_id, product_type""",
                (rs, i) -> new OpenGroup(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getInt(4)),
                period, Timestamp.from(runAt));
    }

    @Override
    public Optional<Run> lastRun() {
        return jdbc.query("SELECT period, run_at FROM commission.liquidation_runs ORDER BY period DESC, run_at DESC LIMIT 1",
                (rs, i) -> new Run(rs.getString(1), rs.getTimestamp(2).toInstant())).stream().findFirst();
    }

    private LotFigures lot(ResultSet rs, int i) throws SQLException {
        return new LotFigures(rs.getObject("batch_id", UUID.class), rs.getObject("beneficiary_party_id", UUID.class),
                rs.getString("product_type"), rs.getString("period"), rs.getString("status"),
                rs.getBigDecimal("subtotal"), rs.getBigDecimal("iva_amount"), rs.getBigDecimal("isr_withheld"),
                rs.getBigDecimal("iva_withheld"), rs.getBigDecimal("total_amount"), rs.getBigDecimal("net_amount"),
                rs.getInt("records_count"), orZero(rs.getBigDecimal("records_subtotal")),
                orZero(rs.getBigDecimal("records_iva")), rs.getInt("records_found"), rs.getInt("payout_attempts"),
                rs.getString("failure_reason"), rs.getTimestamp("created_at").toInstant());
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
