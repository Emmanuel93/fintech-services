package com.fintech.commission.infrastructure.adapter.out.persistence;

import com.fintech.commission.application.port.out.LiquidationRunLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
class JdbcLiquidationRunLog implements LiquidationRunLog {

    private final JdbcTemplate jdbc;

    JdbcLiquidationRunLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(String period, Instant runAt, int lotsOpened, int carriedOver, int waiting, int retried) {
        jdbc.update("""
                INSERT INTO commission.liquidation_runs (period, run_at, lots_opened, carried_over, waiting, retried)
                VALUES (?, ?, ?, ?, ?, ?)""", period, Timestamp.from(runAt), lotsOpened, carriedOver, waiting, retried);
    }
}
