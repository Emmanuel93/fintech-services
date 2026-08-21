package com.fintech.channelmobile.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * Registro de acceso del canal móvil en la bitácora regulatoria.
 *
 * <p>Existe porque este canal <b>no auditaba nada</b>. Todo lo que hacen clientes, distribuidores
 * y empresas desde la app era invisible para una revisión: la bitácora sólo conocía al personal
 * interno, y preguntas como «quién consultó el expediente de este cliente» o «desde qué IP se
 * entró a esta cuenta» se respondían con media verdad, la del backoffice.
 *
 * <p>El envío es asíncrono y los fallos se tragan: si la bitácora está caída, la app sigue
 * funcionando. Con escritura síncrona, un problema del registro tumbaría la operación del cliente
 * — el remedio sería peor que el hueco.
 */
@Component
public class MobileAuditClient {

    private static final Logger log = LoggerFactory.getLogger(MobileAuditClient.class);

    private final WebClient webClient;

    public MobileAuditClient(@Qualifier("auditWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

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

    /** Calza campo por campo con RecordAccessRequest de audit-service. */
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
}
