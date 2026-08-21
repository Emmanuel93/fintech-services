package com.fintech.audit.infrastructure.adapter.in.api.dto;

import com.fintech.audit.application.AccessAuditCommand;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * Un acceso reportado por el canal (BFF): quién entró/consultó/descargó qué, sobre quién, desde
 * dónde y cuándo.
 *
 * <p>La identidad —del actor y del sujeto— la resuelve el canal, que es quien tiene el contexto y
 * las credenciales para preguntar; el núcleo la persiste tal cual, saneando la query. Resolverla
 * aquí, al consultar, no serviría: la bitácora es inmutable y lo que interesa es quién era esa
 * persona <em>entonces</em>, no quién es hoy.
 */
public record RecordAccessRequest(
        @NotBlank String action,

        // ── Quién actuó ──────────────────────────────────────────────────────
        String actor,
        String actorEmail,
        String actorName,
        String actorCurp,
        String actorPhone,
        String actorRoles,
        String actorChannel,

        // ── Sobre quién se actuó ─────────────────────────────────────────────
        String subjectPartyId,
        String subjectName,
        String subjectCurp,
        String subjectEmail,
        String subjectPhone,

        // ── Desde dónde, sobre qué, con qué resultado ────────────────────────
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
) {
    /**
     * @param fallbackActor identidad inyectada por el gateway, por si el canal no la mandó.
     * @param fallbackSource nombre del canal deducido del propio reporte, por si no vino
     *                       {@code domainSource}: sin él la entrada se atribuiría a un canal
     *                       cualquiera, que es peor que no decir nada.
     */
    public AccessAuditCommand toCommand(String fallbackActor, String fallbackSource) {
        String effectiveActor = (actor != null && !actor.isBlank()) ? actor : fallbackActor;
        String effectiveSource = (domainSource != null && !domainSource.isBlank())
                ? domainSource : fallbackSource;
        return new AccessAuditCommand(
                action,
                effectiveActor, actorEmail, actorName, actorCurp, actorPhone,
                actorRoles, actorChannel,
                subjectPartyId, subjectName, subjectCurp, subjectEmail, subjectPhone,
                actorIp, userAgent, sessionId,
                resourceType, resourceId, httpMethod, httpPath, httpQuery,
                outcome, statusCode, durationMs, correlationId, effectiveSource, occurredAt);
    }
}
