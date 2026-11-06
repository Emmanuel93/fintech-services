package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.BusinessCalendarRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Repository
class JdbcBusinessCalendarRepository implements BusinessCalendarRepository {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcBusinessCalendarRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(String calendarCode, LocalDate day, boolean business, String label) {
        jdbc.update("""
                INSERT INTO credit_portfolio.business_calendar_days (calendar_code, day, is_business, label)
                VALUES (:code, :day, :business, :label)
                ON CONFLICT (calendar_code, day) DO UPDATE
                   SET is_business = EXCLUDED.is_business, label = EXCLUDED.label, updated_at = now()""",
                new MapSqlParameterSource("code", calendarCode).addValue("day", day).addValue("business", business)
                        .addValue("label", label));
    }

    @Override
    public Map<LocalDate, Boolean> exceptions(String calendarCode) {
        Map<LocalDate, Boolean> out = new HashMap<>();
        jdbc.query("SELECT day, is_business FROM credit_portfolio.business_calendar_days WHERE calendar_code = :code",
                new MapSqlParameterSource("code", calendarCode),
                (ResultSet rs) -> { out.put(rs.getObject("day", LocalDate.class), rs.getBoolean("is_business")); });
        return out;
    }
}
