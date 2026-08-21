package com.fintech.audit.application;

import java.time.Instant;

/**
 * Un acceso a auditar: quién (identidad completa + roles + canal + IP + user-agent + sesión), sobre
 * qué (recurso + id + ruta HTTP), sobre quién, con qué resultado y cuándo. Lo emite el canal (BFF)
 * por cada request; el núcleo lo persiste inmutable.
 *
 * <p>La identidad del actor llega ya resuelta y se guarda tal cual: es un <em>snapshot</em> de quién
 * era esa persona en ese momento, no un puntero a quién es hoy.
 */
public record AccessAuditCommand(
        String action,          // VIEW | SEARCH | DOWNLOAD | EXPORT | READ | MUTATION | LOGIN

        // ── Quién actuó ──────────────────────────────────────────────────────
        String actor,           // staffUserId del colaborador, o prospectId del cliente
        String actorEmail,
        String actorName,
        String actorCurp,       // exigido para identificar plenamente a una persona física
        String actorPhone,      // sólo para cliente/distribuidor; el colaborador no lo aporta
        String actorRoles,      // "CREDIT_ANALYST,ADMIN"
        String actorChannel,    // BACKOFFICE | MOBILE

        // ── Sobre quién se actuó ─────────────────────────────────────────────
        String subjectPartyId,  // se persiste en party_id: el cliente al que concierne la entrada
        String subjectName,
        String subjectCurp,
        String subjectEmail,
        String subjectPhone,

        // ── Desde dónde, sobre qué, con qué resultado ────────────────────────
        String actorIp,
        String userAgent,
        String sessionId,
        String resourceType,    // portfolio | clients | application | audit | dashboard ...
        String resourceId,      // id concreto, si aplica
        String httpMethod,
        String httpPath,
        String httpQuery,
        String outcome,         // SUCCESS | DENIED | ERROR
        Integer statusCode,
        Long durationMs,
        String correlationId,

        /** El servicio que reporta. Sin él toda entrada quedaba atribuida al backoffice. */
        String domainSource,

        Instant occurredAt) {
}
