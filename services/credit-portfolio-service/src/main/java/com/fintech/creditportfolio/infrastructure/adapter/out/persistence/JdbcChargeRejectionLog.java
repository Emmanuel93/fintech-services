package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.ChargeRejectionLog;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Repository
class JdbcChargeRejectionLog implements ChargeRejectionLog {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcChargeRejectionLog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(String sourceEventId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM credit_portfolio.charge_rejections WHERE source_event_id = :id",
                new MapSqlParameterSource("id", sourceEventId), Integer.class);
        return n != null && n > 0;
    }

    @Override
    public void record(String sourceEventId, UUID creditAccountId, String chargeType, BigDecimal amount,
                       LocalDate valueDate, LocalDate dayCloseDate, String reason) {
        jdbc.update("""
                INSERT INTO credit_portfolio.charge_rejections
                       (source_event_id, credit_account_id, charge_type, amount, value_date, day_close_date, reason)
                VALUES (:id, :cuenta, :tipo, :importe, :fecha, :lote, :motivo)
                ON CONFLICT (source_event_id) DO NOTHING""",
                new MapSqlParameterSource("id", sourceEventId).addValue("cuenta", creditAccountId)
                        .addValue("tipo", chargeType).addValue("importe", amount)
                        .addValue("fecha", valueDate).addValue("lote", dayCloseDate).addValue("motivo", reason));
    }
}
