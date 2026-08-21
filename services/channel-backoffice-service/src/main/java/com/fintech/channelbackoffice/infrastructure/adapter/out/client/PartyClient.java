package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Acceso a party-service.
 *
 * <p>Hoy solo se puede consultar por id: la <strong>búsqueda de clientes</strong> (por nombre, CURP
 * o RFC) y el {@code /batch?ids=} que evita el N+1 en los listados son endpoints net-new de la
 * fase 1 — sin ellos no hay pantalla de búsqueda.
 */
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
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("party-service", r))
                .bodyToMono(PartyResponse.class)
                .block();
    }

    /**
     * Busca por prospecto.
     *
     * <p>Cartera guarda como obligado el id del prospecto, no el de la party:
     * son entidades distintas y buscar por `partyId` con ese valor devuelve 404.
     * Devuelve `null` si no existe, para que el llamador decida —una fila sin
     * nombre es mejor que un listado caído.
     */
    public PartyResponse getByProspectId(UUID prospectId) {
        log.info("-> GET party-service /api/v1/parties/by-prospect/{}", prospectId);
        try {
            return webClient.get()
                    .uri("/api/v1/parties/by-prospect/{prospectId}", prospectId)
                    .retrieve()
                    .bodyToMono(PartyResponse.class)
                    .block();
        } catch (Exception ex) {
            log.debug("party-service sin party para prospectId={}: {}", prospectId, ex.getMessage());
            return null;
        }
    }

    /**
     * Búsqueda paginada de clientes (por nombre, CURP o RFC). Es la que sostiene
     * la pantalla de clientes del backoffice; la página la arma party-service.
     */
    public PageResponse<PartyResponse> search(String q, String type, String status,
                                              String executiveId, int page, int size, String sort) {
        log.info("-> GET party-service /api/v1/parties q={} page={} size={}", q, page, size);
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/parties")
                            .queryParam("page", page)
                            .queryParam("size", size);
                    if (q != null && !q.isBlank())                   b.queryParam("q", q);
                    if (type != null && !type.isBlank())             b.queryParam("type", type);
                    if (status != null && !status.isBlank())         b.queryParam("status", status);
                    if (executiveId != null && !executiveId.isBlank()) b.queryParam("executiveId", executiveId);
                    if (sort != null && !sort.isBlank())             b.queryParam("sort", sort);
                    return b.build();
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("party-service", r))
                .bodyToMono(new ParameterizedTypeReference<PageResponse<PartyResponse>>() {})
                .block();
    }

    /** Asigna (o reasigna) el ejecutivo de cuenta de un cliente. */
    public PartyResponse assignExecutive(UUID partyId, UUID executiveId, String executiveName) {
        log.info("-> PUT party-service /api/v1/parties/{}/executive exec={}", partyId, executiveId);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("executiveId", executiveId == null ? null : executiveId.toString());
        body.put("executiveName", executiveName);
        return webClient.put()
                .uri("/api/v1/parties/{partyId}/executive", partyId)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("party-service", r))
                .bodyToMono(PartyResponse.class)
                .block();
    }

    /**
     * Hidratación por lote: varios parties en una sola llamada. {@code by} elige
     * la clave — {@code partyId} o {@code prospectId} (cartera guarda el prospecto
     * como obligado). Es lo que evita el N+1 al pintar nombres en un listado.
     */
    public List<PartyResponse> batch(Collection<UUID> ids, String by) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        log.info("-> GET party-service /api/v1/parties/batch by={} ids={}", by, ids.size());
        return webClient.get()
                .uri(uri -> uri.path("/api/v1/parties/batch")
                        .queryParam("ids", ids.toArray())
                        .queryParam("by", by)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("party-service", r))
                .bodyToFlux(PartyResponse.class)
                .collectList()
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
            UUID      assignedExecutiveId,
            String    assignedExecutiveName,
            Instant   createdAt
    ) {}

    /** La página tal como la serializa Spring Data. */
    public record PageResponse<T>(
            List<T> content,
            int     number,
            int     size,
            long    totalElements,
            int     totalPages
    ) {}
}
