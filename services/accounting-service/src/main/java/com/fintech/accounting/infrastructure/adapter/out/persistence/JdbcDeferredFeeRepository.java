package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.DeferredFeeRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcDeferredFeeRepository implements DeferredFeeRepository {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcDeferredFeeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean register(DeferredFee f) {
        return jdbc.update("""
                INSERT INTO accounting.deferred_fees
                       (deferred_fee_id, source_event_id, credit_account_id, obligor_party_id, org_unit_code, total, months,
                        first_period, kind)
                VALUES (:id, :source, :account, :party, :unit, :total, :months, :first, :kind)
                ON CONFLICT (source_event_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", f.id()).addValue("source", f.sourceEventId()).addValue("account", f.creditAccountId())
                .addValue("party", f.obligorPartyId()).addValue("unit", f.orgUnitCode()).addValue("total", f.total())
                .addValue("months", f.months()).addValue("first", f.firstPeriod())
                .addValue("kind", f.kind() == null ? FEE : f.kind())) > 0;
    }

    @Override
    public List<DeferredFee> active() {
        return jdbc.query("SELECT * FROM accounting.deferred_fees WHERE status = 'ACTIVE' ORDER BY first_period, deferred_fee_id",
                new MapSqlParameterSource(), ROW);
    }

    @Override
    public List<DeferredFee> activeOf(UUID creditAccountId) {
        return jdbc.query("SELECT * FROM accounting.deferred_fees WHERE status = 'ACTIVE' AND credit_account_id = :account",
                new MapSqlParameterSource("account", creditAccountId), ROW);
    }

    @Override
    public Optional<DeferredFee> findBySource(String sourceEventId) {
        return jdbc.query("SELECT * FROM accounting.deferred_fees WHERE source_event_id = :source",
                new MapSqlParameterSource("source", sourceEventId), ROW).stream().findFirst();
    }

    @Override
    public void recognize(UUID id, BigDecimal recognizedSoFar) {
        jdbc.update("""
                UPDATE accounting.deferred_fees
                   SET recognized = :recognized,
                       status = CASE WHEN :recognized >= total THEN 'DONE' ELSE 'ACTIVE' END,
                       updated_at = now()
                 WHERE deferred_fee_id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("recognized", recognizedSoFar));
    }

    @Override
    public void reduceTotal(UUID id, BigDecimal amount) {
        jdbc.update("""
                UPDATE accounting.deferred_fees
                   SET total = total - :amount,
                       status = CASE WHEN total - :amount <= recognized THEN 'DONE' ELSE 'ACTIVE' END,
                       updated_at = now()
                 WHERE deferred_fee_id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("amount", amount));
    }

    private static final RowMapper<DeferredFee> ROW = (rs, i) -> new DeferredFee(
            rs.getObject("deferred_fee_id", UUID.class), rs.getString("source_event_id"),
            rs.getObject("credit_account_id", UUID.class), rs.getObject("obligor_party_id", UUID.class),
            rs.getString("org_unit_code"), rs.getBigDecimal("total"), rs.getBigDecimal("recognized"),
            rs.getInt("months"), rs.getString("first_period"), rs.getString("kind"));
}
