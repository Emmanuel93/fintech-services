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
import java.time.LocalDate;
import java.util.UUID;

@Component
public class PartyClient {

    private static final Logger log = LoggerFactory.getLogger(PartyClient.class);

    private final WebClient webClient;

    public PartyClient(@Qualifier("partyWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public PartyResponse getByPartyId(UUID partyId) {
        log.info("-> GET party-service /api/v1/parties/{}", partyId);
        return webClient.get()
                .uri("/api/v1/parties/{partyId}", partyId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new ResponseStatusException(resp.statusCode(),
                                "party-service error: " + resp.statusCode())))
                .bodyToMono(PartyResponse.class)
                .doOnNext(r -> log.info("<- party-service 200 partyId={}", r.partyId()))
                .block();
    }

    /**
     * El JWT del gateway trae sub = prospectId (identity firma con el id del prospecto),
     * pero party-service genera un partyId propio ligado al prospectId. Por eso el perfil
     * se resuelve por prospectId, no por partyId.
     */
    public PartyResponse getByProspectId(UUID prospectId) {
        log.info("-> GET party-service /api/v1/parties/by-prospect/{}", prospectId);
        return webClient.get()
                .uri("/api/v1/parties/by-prospect/{prospectId}", prospectId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new ResponseStatusException(resp.statusCode(),
                                "party-service error: " + resp.statusCode())))
                .bodyToMono(PartyResponse.class)
                .doOnNext(r -> log.info("<- party-service 200 partyId={} prospectId={}", r.partyId(), r.prospectId()))
                .block();
    }

    public record PartyResponse(
            UUID      partyId,
            UUID      prospectId,
            UUID      evaluationId,
            String    partyType,
            String    status,
            String    firstName,
            String    lastName1,
            String    lastName2,
            String    curp,
            String    rfc,
            LocalDate dateOfBirth,
            String    riskLevel,
            Integer   totalScore,
            Instant   createdAt
    ) {}
}
