package com.fintech.stp.infrastructure.adapter.out.http.stub;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An amount a test armed: with {@code scenario-amounts: ARMED}, only these play the scenario their cents name.
 *
 * <p>Persisted, like the stub's orders, so a replica or a restart sees the same. The table stays empty in production,
 * where the stub refuses to start.
 */
@Entity
@Table(schema = "stp", name = "stub_scenario_amounts")
public class StubScenarioAmount {

    @Id
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "armed_at", nullable = false, updatable = false)
    private Instant armedAt;

    protected StubScenarioAmount() {}

    StubScenarioAmount(BigDecimal amount, Instant armedAt) {
        this.amount = amount;
        this.armedAt = armedAt;
    }

    public BigDecimal getAmount() { return amount; }
    public Instant getArmedAt()   { return armedAt; }
}
