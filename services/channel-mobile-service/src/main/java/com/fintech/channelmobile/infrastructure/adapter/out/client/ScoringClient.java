package com.fintech.channelmobile.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Proxy a scoring-service para la precalificación del home ("créditos disponibles").
 * Solo lectura sobre el motor de reglas — no crea score_evaluations ni publica eventos
 * (ver ScoringEvaluationService.prequalify en scoring-service).
 */
@Component
public class ScoringClient {

    private static final Logger log = LoggerFactory.getLogger(ScoringClient.class);

    private final WebClient webClient;

    public ScoringClient(@Qualifier("scoringWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public PrequalificationResponse prequalify(UUID prospectId, String prospectType, List<String> productTypes) {
        log.info("-> POST scoring-service /api/v1/scoring/prequalify/{} prospectType={} productTypes={}",
                prospectId, prospectType, productTypes);
        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/scoring/prequalify/{prospectId}")
                        .queryParam("prospectType", prospectType)
                        .build(prospectId))
                .bodyValue(new PrequalifyRequestPayload(productTypes))
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        resp.bodyToMono(String.class).flatMap(detail ->
                                Mono.error(new ResponseStatusException(resp.statusCode(), detail))))
                .bodyToMono(PrequalificationResponse.class)
                .doOnNext(r -> log.info("<- scoring-service prequalify prospectId={} results={}",
                        prospectId, r.results().size()))
                .block();
    }

    public record PrequalifyRequestPayload(List<String> productTypes) {}

    public record PrequalificationItem(
            String productType, boolean evaluated, String skippedReason,
            String decision, String riskLevel, int totalScore) {}

    public record PrequalificationResponse(UUID prospectId, Instant computedAt, List<PrequalificationItem> results) {}
}
