package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Acceso a compliance-service (D16) para la mesa de cumplimiento.
 *
 * <p>Compliance no está enrutado hacia fuera, y está bien que no lo esté: adjudicar es una facultad
 * de backoffice, no una API pública. Hasta ahora la única forma de dictaminar era por la red
 * interna de Docker, firmando como un script; este cliente es lo que le pone nombre y apellido.
 *
 * <p>El autor de cada dictamen lo pone este BFF desde la sesión —{@code decidedBy}— y nunca el
 * navegador: compliance confía en lo que le dicen, así que quien firma tiene que salir de aquí.
 */
@Component
public class ComplianceClient {

    private static final Logger log = LoggerFactory.getLogger(ComplianceClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public ComplianceClient(@Qualifier("complianceWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /** La cola, paginada y filtrada en el dueño. Vacío en un filtro = todos. */
    public Map<String, Object> search(Collection<String> identityStatuses,
                                      Collection<String> screeningStatuses,
                                      String q, int page, int size) {
        log.info("-> GET compliance /verifications identidad={} listas={} q={}",
                identityStatuses, screeningStatuses, q == null ? "" : "«" + q + "»");
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/compliance/verifications")
                            .queryParam("page", page)
                            .queryParam("size", size);
                    if (identityStatuses != null && !identityStatuses.isEmpty()) {
                        b.queryParam("identityStatus", identityStatuses.toArray());
                    }
                    if (screeningStatuses != null && !screeningStatuses.isEmpty()) {
                        b.queryParam("screeningStatus", screeningStatuses.toArray());
                    }
                    if (q != null && !q.isBlank()) b.queryParam("q", q.trim());
                    return b.build();
                })
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("compliance-service", r))
                .bodyToMono(MAP)
                .block();
    }

    public Map<String, Object> verification(UUID verificationId) {
        log.info("-> GET compliance /verifications/{}", verificationId);
        return webClient.get()
                .uri("/api/v1/compliance/verifications/{id}", verificationId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("compliance-service", r))
                .bodyToMono(MAP)
                .block();
    }

    /** El veredicto de una persona por su prospecto. Vacío si todavía no tiene. */
    public Optional<Map<String, Object>> byProspect(UUID prospectId) {
        try {
            return Optional.ofNullable(webClient.get()
                    .uri("/api/v1/compliance/verifications/by-prospect/{id}", prospectId)
                    .headers(DomainClientSupport.staffIdentity())
                    .retrieve()
                    .bodyToMono(MAP)
                    .block());
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) return Optional.empty();
            throw e;
        }
    }

    /** Las consultas a listas de una persona: la bitácora que pide una revisión. */
    public Map<String, Object> checks(UUID prospectId, int limit) {
        return webClient.get()
                .uri(uri -> uri.path("/api/v1/compliance/verifications/by-prospect/{id}/checks")
                        .queryParam("limit", limit).build(prospectId))
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("compliance-service", r))
                .bodyToMono(MAP)
                .block();
    }

    /** Dictamen de identidad: {@code VERIFIED} o {@code REJECTED}, con autor y argumento. */
    public Map<String, Object> decideIdentity(UUID verificationId, String decision,
                                              String decidedBy, String notes) {
        log.info("-> POST compliance /verifications/{}/identity {} por {}", verificationId, decision, decidedBy);
        return post("/api/v1/compliance/verifications/{id}/identity", verificationId, decision, decidedBy, notes);
    }

    /** Adjudicación de listas: {@code CLEARED} o {@code CONFIRMED_MATCH}, con autor y argumento. */
    public Map<String, Object> adjudicate(UUID verificationId, String decision,
                                          String decidedBy, String notes) {
        log.info("-> POST compliance /verifications/{}/adjudicate {} por {}", verificationId, decision, decidedBy);
        return post("/api/v1/compliance/verifications/{id}/adjudicate", verificationId, decision, decidedBy, notes);
    }

    private Map<String, Object> post(String path, UUID id, String decision, String decidedBy, String notes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", decision);
        body.put("decidedBy", decidedBy);
        body.put("notes", notes);
        return webClient.post()
                .uri(path, id)
                .headers(DomainClientSupport.staffIdentity())
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("compliance-service", r))
                .bodyToMono(MAP)
                .block();
    }
}
