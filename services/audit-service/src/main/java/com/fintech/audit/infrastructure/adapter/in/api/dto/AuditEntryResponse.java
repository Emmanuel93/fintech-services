package com.fintech.audit.infrastructure.adapter.in.api.dto;

import com.fintech.audit.domain.AuditEntry;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public record AuditEntryResponse(
        UUID entryId,
        String category,
        String eventType,
        String action,
        String domainSource,
        String aggregateId,
        String partyId,
        String correlationId,
        // Quién (legible: correo y nombre; el UUID queda como referencia técnica)
        String actor,
        String actorEmail,
        String actorName,
        String actorCurp,
        String actorPhone,
        String actorRoles,
        String actorChannel,
        String actorIp,
        String userAgent,
        String sessionId,
        // Sobre quién se actuó (su id es partyId)
        String subjectName,
        String subjectCurp,
        String subjectEmail,
        String subjectPhone,
        // Sobre qué
        String resourceType,
        String resourceLabel,
        String resourceId,
        String httpMethod,
        String httpPath,
        String httpQuery,
        // Resultado
        String outcome,
        Integer statusCode,
        Long durationMs,
        // Cuándo (instante UTC + etiqueta legible con hora en horario de México)
        Instant occurredAt,
        String occurredAtLabel,
        Instant createdAt,
        String createdAtLabel
) {
    static final ZoneId ZONE = ZoneId.of("America/Mexico_City");
    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    static String label(Instant instant) {
        return instant == null ? null : FMT.format(instant.atZone(ZONE));
    }

    /** El "sobre qué" en español, para que el recurso diga algo sin tener que resolver el UUID. */
    static String resourceLabel(String resourceType) {
        if (resourceType == null) return null;
        return switch (resourceType.toLowerCase()) {
            case "portfolio"                     -> "Cartera de crédito";
            case "clientes", "clients", "parties" -> "Clientes";
            case "solicitudes", "applications", "origination" -> "Solicitudes de crédito";
            case "products", "productos"         -> "Productos";
            case "audit", "auditoria"            -> "Auditoría";
            case "dashboard"                     -> "Tablero";
            case "sales-org", "salesorg"         -> "Red comercial";
            case "executives", "ejecutivos"      -> "Ejecutivos";
            case "permissions", "permisos"       -> "Permisos";
            case "staff", "personal"             -> "Personal";
            case "commissions", "comisiones"     -> "Comisiones";
            case "disbursements", "desembolsos"  -> "Desembolsos";
            case "scoring"                       -> "Scoring";
            default -> Character.toUpperCase(resourceType.charAt(0)) + resourceType.substring(1);
        };
    }

    public static AuditEntryResponse from(AuditEntry e) {
        return new AuditEntryResponse(
                e.getEntryId(), e.getCategory(), e.getEventType(), e.getAction(), e.getDomainSource(),
                e.getAggregateId(), e.getPartyId(), e.getCorrelationId(),
                e.getActor(), e.getActorEmail(), e.getActorName(), e.getActorCurp(), e.getActorPhone(),
                e.getActorRoles(), e.getActorChannel(),
                e.getActorIp(), e.getUserAgent(), e.getSessionId(),
                e.getSubjectName(), e.getSubjectCurp(), e.getSubjectEmail(), e.getSubjectPhone(),
                e.getResourceType(), resourceLabel(e.getResourceType()), e.getResourceId(),
                e.getHttpMethod(), e.getHttpPath(), e.getHttpQuery(),
                e.getOutcome(), e.getStatusCode(), e.getDurationMs(),
                e.getOccurredAt(), label(e.getOccurredAt()), e.getCreatedAt(), label(e.getCreatedAt()));
    }
}
