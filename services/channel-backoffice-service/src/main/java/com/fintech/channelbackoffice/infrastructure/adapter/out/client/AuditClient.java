package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Acceso a audit-service: la bitácora regulatoria. Passthrough delgado —el BFF no compone la
 * auditoría con nada—; reenvía la identidad del empleado y traduce errores. La política de acceso
 * (solo roles de auditoría) la aplica el controlador del BFF.
 */
@Component
public class AuditClient {

    private static final Logger log = LoggerFactory.getLogger(AuditClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public AuditClient(@Qualifier("auditWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Tope de entradas que este canal pide por consulta.
     *
     * <p>audit-service ya acota del lado del servidor; esto lo hace explícito desde aquí para que
     * el número que la consola muestra sea una decisión del canal y no lo que por casualidad
     * traiga el default del otro servicio.
     */
    private static final int DEFAULT_LIMIT = 200;

    public List<Map<String, Object>> listEntries(String partyId, String aggregateId, String eventType,
                                                 String actor, String from, String to) {
        return listEntries(partyId, aggregateId, eventType, actor, from, to, DEFAULT_LIMIT);
    }

    public List<Map<String, Object>> listEntries(String partyId, String aggregateId, String eventType,
                                                 String actor, String from, String to, int limit) {
        log.info("-> GET audit-service /api/v1/audit/entries actor={} eventType={} limit={}",
                actor, eventType, limit);
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/audit/entries");
                    if (partyId != null && !partyId.isBlank())         b.queryParam("partyId", partyId);
                    if (aggregateId != null && !aggregateId.isBlank()) b.queryParam("aggregateId", aggregateId);
                    if (eventType != null && !eventType.isBlank())     b.queryParam("eventType", eventType);
                    if (actor != null && !actor.isBlank())             b.queryParam("actor", actor);
                    if (from != null && !from.isBlank())               b.queryParam("from", from);
                    if (to != null && !to.isBlank())                   b.queryParam("to", to);
                    b.queryParam("limit", limit);
                    return b.build();
                })
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("audit-service", r))
                .bodyToMono(LIST)
                .block();
    }

    /**
     * Registra un acceso (pantalla/búsqueda/descarga/consumo) en la bitácora. <b>Fire-and-forget</b>:
     * no bloquea ni puede tumbar la respuesta al operador —la auditoría de acceso nunca debe volver
     * frágil la navegación—; si audit-service no responde, se pierde ese registro y se deja traza en
     * el log local. La identidad se congela al invocar (hilo del request), no al suscribir.
     */
    public void logAccess(AccessLog access, Consumer<HttpHeaders> identity) {
        webClient.post()
                .uri("/api/v1/audit/access")
                .headers(identity)
                .bodyValue(access)
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(e -> {
                    log.debug("audit access log descartado (audit-service no disponible): {}", e.toString());
                    return Mono.empty();
                })
                .subscribe();
    }

    /** El acceso tal como lo envía el canal; calza con RecordAccessRequest de audit-service. */
    public record AccessLog(
            String action,
            String actor,
            String actorEmail,
            String actorName,
            String actorCurp,
            String actorPhone,
            String actorRoles,
            String actorChannel,
            String subjectPartyId,
            String subjectName,
            String subjectCurp,
            String subjectEmail,
            String subjectPhone,
            String actorIp,
            String userAgent,
            String sessionId,
            String resourceType,
            String resourceId,
            String httpMethod,
            String httpPath,
            String httpQuery,
            String outcome,
            Integer statusCode,
            Long durationMs,
            String correlationId,
            String domainSource,
            Instant occurredAt
    ) {}

    public Map<String, Object> getEntry(UUID entryId) {
        log.info("-> GET audit-service /api/v1/audit/entries/{}", entryId);
        return webClient.get()
                .uri("/api/v1/audit/entries/{id}", entryId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("audit-service", r))
                .bodyToMono(MAP)
                .block();
    }
}
