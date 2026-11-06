package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each service says of itself: the self-checks of commissions, treasury, the portfolio and the statements, read
 * as they come (the same contract everywhere) so the console shows them without the BFF knowing any check.
 *
 * <p>A service that does not answer is reported as such, never as «in order»: silence is not a clean bill.
 */
@Component
public class SelfCheckClient {

    private static final Logger log = LoggerFactory.getLogger(SelfCheckClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    public static final String UNREACHABLE = "UNREACHABLE";

    private record Source(String service, WebClient client, String path) {}

    private final List<Source> sources;

    public SelfCheckClient(@Qualifier("commissionWebClient") WebClient commission,
                           @Qualifier("bankingWebClient") WebClient banking,
                           @Qualifier("creditPortfolioWebClient") WebClient portfolio,
                           @Qualifier("statementWebClient") WebClient statements) {
        this.sources = List.of(
                new Source("commission-service", commission, "/api/v1/commissions/self-checks"),
                new Source("banking-service", banking, "/api/v1/treasury/self-checks"),
                new Source("credit-portfolio-service", portfolio, "/api/v1/portfolio/self-checks"),
                new Source("statement-service", statements, "/api/v1/statements/self-checks"));
    }

    /** Every service's report, in a fixed order. */
    public List<Map<String, Object>> all() {
        return sources.stream().map(this::report).toList();
    }

    private Map<String, Object> report(Source s) {
        try {
            Map<String, Object> r = s.client().get().uri(s.path())
                    .headers(DomainClientSupport.staffIdentity())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block(TIMEOUT);
            if (r != null) return r;
        } catch (Exception e) {
            log.warn("{} no entregó su autoverificación: {}", s.service(), e.getMessage());
            return unreachable(s.service(), e.getMessage());
        }
        return unreachable(s.service(), "respuesta vacía");
    }

    private static Map<String, Object> unreachable(String service, String why) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("service", service);
        r.put("outcome", UNREACHABLE);
        r.put("error", why);
        r.put("checks", List.of());
        return r;
    }
}
