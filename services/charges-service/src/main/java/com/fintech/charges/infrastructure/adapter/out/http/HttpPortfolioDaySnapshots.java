package com.fintech.charges.infrastructure.adapter.out.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.charges.application.port.out.PortfolioDaySnapshots;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The portfolio's day-snapshot API over HTTP. Charges identifies itself as a service with the same
 * headers the statement service uses; the portfolio answers 200 when the snapshot exists, 202 with the
 * reason while it cannot be taken yet, and 409 while another process is taking it.
 */
@Component
class HttpPortfolioDaySnapshots implements PortfolioDaySnapshots {

    private static final String SERVICE_ID = "charges-service";

    private final RestClient portfolio;

    HttpPortfolioDaySnapshots(@Qualifier("creditPortfolioRestClient") RestClient portfolio) {
        this.portfolio = portfolio;
    }

    @Override
    public Status take(LocalDate businessDate) {
        try {
            ResponseEntity<TakeResponse> r = portfolio.post()
                    .uri("/api/v1/portfolio/day-snapshots/{d}?triggeredBy={by}", businessDate, SERVICE_ID)
                    .header("X-User-Id", SERVICE_ID).header("X-Roles", "SERVICE")
                    .retrieve()
                    .toEntity(TakeResponse.class);
            TakeResponse body = r.getBody();
            boolean taken = r.getStatusCode().equals(HttpStatus.OK) && body != null && body.snapshot() != null;
            return new Status(taken, body != null ? body.detail() : r.getStatusCode().toString(),
                    taken && body.snapshot().accounts() != null ? body.snapshot().accounts() : 0);
        } catch (RestClientResponseException e) {
            // 409 (someone else is taking it) and 5xx: not taken, with what the portfolio said.
            return new Status(false, "cartera respondió " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), 0);
        }
    }

    @Override
    public List<AccountSnapshot> page(LocalDate businessDate, UUID after, int size) {
        String uri = after == null
                ? "/api/v1/portfolio/day-snapshots/{d}/accounts?size={n}"
                : "/api/v1/portfolio/day-snapshots/{d}/accounts?size={n}&after={after}";
        List<AccountRow> rows = portfolio.get()
                .uri(uri, businessDate, size, after)
                .header("X-User-Id", SERVICE_ID).header("X-Roles", "SERVICE")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return rows == null ? List.of() : rows.stream().map(AccountRow::toSnapshot).toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TakeResponse(String outcome, String detail, DayRow snapshot) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DayRow(String status, Integer accounts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountRow(UUID creditAccountId, BigDecimal principal, BigDecimal overduePrincipal, LocalDate oldestDueDate,
                      int daysDelinquent, long balanceVersion, Instant takenAt, BigDecimal interestBearingPrincipal,
                      BigDecimal scheduledInterest, BigDecimal scheduledTax) {

        AccountSnapshot toSnapshot() {
            return new AccountSnapshot(creditAccountId, principal, overduePrincipal, oldestDueDate, daysDelinquent,
                    balanceVersion, takenAt, interestBearingPrincipal != null ? interestBearingPrincipal : principal,
                    scheduledInterest, scheduledTax);
        }
    }
}
