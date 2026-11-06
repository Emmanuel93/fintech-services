package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.TreasuryCashBookRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Repository
class JdbcTreasuryCashBookRepository implements TreasuryCashBookRepository {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMM");

    private final NamedParameterJdbcTemplate jdbc;

    JdbcTreasuryCashBookRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID movementId, String movementType, String direction, BigDecimal amount, LocalDate businessDate,
                       String reference, String sourceEventId, LocalDate portfolioDate, String trackingKey) {
        jdbc.update("""
                INSERT INTO accounting.treasury_cash_book (movement_id, movement_type, direction, amount, business_date,
                       period, reference, source_event_id, portfolio_date, tracking_key)
                VALUES (:id, :type, :direction, :amount, :day, :period, :reference, :source, :portfolioDay, :tracking)
                ON CONFLICT (movement_id) DO NOTHING""",
                new MapSqlParameterSource("id", movementId).addValue("type", movementType)
                        .addValue("direction", direction).addValue("amount", amount).addValue("day", businessDate)
                        .addValue("period", businessDate.format(PERIOD)).addValue("reference", reference)
                        .addValue("source", sourceEventId)
                        .addValue("portfolioDay", portfolioDate != null ? portfolioDate : businessDate)
                        .addValue("tracking", trackingKey));
    }
}
