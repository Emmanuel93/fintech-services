package com.fintech.closing.infrastructure.adapter.out.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.closing.application.port.out.PortfolioDaySnapshots;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The portfolio's day-snapshot lookup over HTTP, in batches, identified as a service like the statement service does. */
@Component
class HttpPortfolioDaySnapshots implements PortfolioDaySnapshots {

    private static final String SERVICE_ID = "closing-service";
    private static final int BATCH = 500;

    private final RestClient portfolio;

    HttpPortfolioDaySnapshots(@Qualifier("creditPortfolioRestClient") RestClient portfolio) {
        this.portfolio = portfolio;
    }

    @Override
    public List<AccountSnapshot> lookup(LocalDate businessDate, Collection<UUID> creditAccountIds) {
        List<UUID> ids = new ArrayList<>(creditAccountIds);
        List<AccountSnapshot> out = new ArrayList<>(ids.size());
        for (int from = 0; from < ids.size(); from += BATCH) {
            List<UUID> batch = ids.subList(from, Math.min(from + BATCH, ids.size()));
            List<AccountRow> rows = portfolio.post()
                    .uri("/api/v1/portfolio/day-snapshots/{d}/accounts/lookup", businessDate)
                    .header("X-User-Id", SERVICE_ID).header("X-Roles", "SERVICE")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(batch)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (rows != null) rows.forEach(r -> out.add(r.toSnapshot()));
        }
        return out;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountRow(UUID creditAccountId, BigDecimal principal, int daysDelinquent, Instant takenAt) {

        AccountSnapshot toSnapshot() {
            return new AccountSnapshot(creditAccountId, principal, daysDelinquent, takenAt);
        }
    }
}
