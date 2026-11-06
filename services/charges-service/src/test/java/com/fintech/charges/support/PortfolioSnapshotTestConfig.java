package com.fintech.charges.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** Replaces the HTTP client to the portfolio with {@link FakePortfolioDaySnapshots} in integration tests. */
@TestConfiguration
public class PortfolioSnapshotTestConfig {

    @Bean
    @Primary
    public FakePortfolioDaySnapshots fakePortfolioDaySnapshots(JdbcTemplate jdbc) {
        return new FakePortfolioDaySnapshots(jdbc);
    }
}
