package com.fintech.statement.infrastructure.adapter.out.persistence;

import com.fintech.statement.application.port.out.StatementSelfCheckQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
class JdbcStatementSelfCheckQueries implements StatementSelfCheckQueries {

    private final JdbcTemplate jdbc;

    JdbcStatementSelfCheckQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int sealedRuns() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM statement.statement_batches WHERE cutoff_status = 'SEALED'",
                Integer.class);
        return n == null ? 0 : n;
    }

    @Override
    public List<RunGap> runGaps() {
        return jdbc.query("""
                SELECT b.run_id, b.business_date, b.cutoff_done, count(s.statement_id)
                  FROM statement.statement_batches b
                  LEFT JOIN statement.statements s ON s.run_id = b.run_id
                 WHERE b.cutoff_status = 'SEALED'
                 GROUP BY b.run_id, b.business_date, b.cutoff_done
                HAVING count(s.statement_id) <> b.cutoff_done
                 ORDER BY b.business_date""",
                (rs, i) -> new RunGap(rs.getObject(1, UUID.class), rs.getDate(2).toLocalDate(), rs.getInt(3), rs.getInt(4)));
    }

    @Override
    public int statements() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM statement.statements", Integer.class);
        return n == null ? 0 : n;
    }

    @Override
    public List<BalanceGap> balanceGaps() {
        // The cutoff the statement was built from travels in its snapshot, as the cutoff event brought it.
        return jdbc.query("""
                SELECT statement_id, contract_number, cutoff_date, balance_at_cutoff,
                       (snapshot -> 'cutoff' ->> 'balanceAtCutoff')::numeric
                  FROM statement.statements
                 WHERE snapshot -> 'cutoff' IS NOT NULL
                   AND abs(coalesce(balance_at_cutoff, 0)
                           - coalesce((snapshot -> 'cutoff' ->> 'balanceAtCutoff')::numeric, 0)) > 0.005
                 ORDER BY cutoff_date""",
                (rs, i) -> new BalanceGap(rs.getObject(1, UUID.class), rs.getString(2), rs.getDate(3).toLocalDate(),
                        rs.getBigDecimal(4), rs.getBigDecimal(5)));
    }

    @Override
    public List<MissingDocument> missingDocuments() {
        return jdbc.query("""
                SELECT s.statement_id, s.contract_number, s.document_bytes, d.size_bytes
                  FROM statement.statements s
                  LEFT JOIN statement.statement_documents d ON d.statement_id = s.statement_id
                 WHERE s.status = 'GENERATED'
                   AND (d.statement_id IS NULL OR d.size_bytes IS NULL OR d.size_bytes <= 0 OR d.sha256 IS NULL
                     OR s.document_bytes IS DISTINCT FROM d.size_bytes)
                 ORDER BY s.cutoff_date""",
                (rs, i) -> new MissingDocument(rs.getObject(1, UUID.class), rs.getString(2), longOrNull(rs, 3),
                        longOrNull(rs, 4)));
    }

    /** A whole number of any width, or null: the document's size is int8 in one table and int4 in the other. */
    private static Long longOrNull(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    @Override
    public List<Ungenerated> ungeneratedBefore(LocalDate cutoffDate) {
        return jdbc.query("""
                SELECT statement_id, contract_number, cutoff_date, status, attempts, last_error
                  FROM statement.statements
                 WHERE status <> 'GENERATED' AND cutoff_date < ?
                 ORDER BY cutoff_date""",
                (rs, i) -> new Ungenerated(rs.getObject(1, UUID.class), rs.getString(2), rs.getDate(3).toLocalDate(),
                        rs.getString(4), rs.getInt(5), rs.getString(6)),
                Date.valueOf(cutoffDate));
    }
}
