package com.fintech.audit.application.service;

import com.fintech.audit.application.AccessAuditCommand;
import com.fintech.audit.application.PayloadSanitizer;
import com.fintech.audit.application.port.out.AuditEntryRepository;
import com.fintech.audit.domain.AuditEntry;
import com.fintech.audit.domain.AuditEntryNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEntryRepository repository;

    public AuditService(AuditEntryRepository repository) {
        this.repository = repository;
    }

    public AuditEntry record(String eventType, String domainSource,
                             String aggregateId, String partyId,
                             String correlationId, String payload) {
        return record(eventType, domainSource, aggregateId, partyId, correlationId, null, payload);
    }

    /**
     * Registra un evento con su ACTOR. El payload se sanea (redacta secretos) antes de persistir:
     * el log es inmutable, un secreto que entra no vuelve a salir.
     */
    public AuditEntry record(String eventType, String domainSource,
                             String aggregateId, String partyId,
                             String correlationId, String actor, String payload) {
        AuditEntry entry = AuditEntry.create(
                eventType, domainSource, aggregateId, partyId, correlationId, actor,
                PayloadSanitizer.sanitize(payload));
        repository.save(entry);
        log.debug("audit entry recorded eventType={} aggregateId={} actor={}", eventType, aggregateId, actor);
        return entry;
    }

    /**
     * Registra un ACCESO (pantalla, búsqueda, descarga, consumo de datos). El query string se sanea
     * como cualquier payload: un token que llegue por la URL no se persiste en claro.
     */
    public AuditEntry recordAccess(AccessAuditCommand cmd) {
        String httpQuery = sanitizeQuery(cmd.httpQuery());
        AuditEntry entry = AuditEntry.access(
                cmd.action(),
                new AuditEntry.ActorIdentity(cmd.actor(), cmd.actorEmail(), cmd.actorName(),
                        cmd.actorCurp(), cmd.actorPhone(), cmd.actorRoles(), cmd.actorChannel()),
                new AuditEntry.SubjectIdentity(cmd.subjectPartyId(), cmd.subjectName(),
                        cmd.subjectCurp(), cmd.subjectEmail(), cmd.subjectPhone()),
                cmd.actorIp(), cmd.userAgent(), cmd.sessionId(),
                cmd.resourceType(), cmd.resourceId(),
                cmd.httpMethod(), cmd.httpPath(), httpQuery,
                cmd.outcome(), cmd.statusCode(), cmd.durationMs(),
                cmd.correlationId(), cmd.domainSource(), cmd.occurredAt(), null);
        repository.save(entry);
        log.debug("access recorded actor={} action={} ip={} path={} status={} source={}",
                cmd.actor(), cmd.action(), cmd.actorIp(), cmd.httpPath(), cmd.statusCode(),
                cmd.domainSource());
        return entry;
    }

    /** Fragmentos que, en el nombre de un parámetro de la URL, marcan su valor como secreto. */
    private static final java.util.Set<String> SENSITIVE_QUERY_KEYS = java.util.Set.of(
            "token", "password", "passwd", "pwd", "secret", "otp", "totp", "pin", "apikey",
            "accesstoken", "refreshtoken", "authorization");

    /** Redacta los valores sensibles de un query string ({@code token=...&format=csv}); nunca en la bitácora. */
    static String sanitizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }
        String[] pairs = query.split("&");
        StringBuilder out = new StringBuilder(query.length());
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String lower = key.toLowerCase();
            boolean sensitive = SENSITIVE_QUERY_KEYS.stream().anyMatch(lower::contains);
            if (eq >= 0 && sensitive) {
                out.append(key).append("=***REDACTED***");
            } else {
                out.append(pair);
            }
            if (i < pairs.length - 1) out.append('&');
        }
        return out.toString();
    }

    /**
     * Tope duro de filas por consulta. No es una preferencia de paginación: es la diferencia entre
     * un servicio que responde y uno que se cae.
     *
     * <p>Esta tabla es la más grande de la plataforma —audit-service consume todos los tópicos— y
     * jamás se depura, porque es un log regulatorio. Una consulta sin cota funciona en desarrollo
     * con mil filas y en producción tumba al llamador (el buffer del WebClient) o al servicio (la
     * memoria). El tope vive aquí, en la capa de aplicación, y no sólo en el controlador: así
     * también cubre a cualquier llamador interno que aparezca después.
     */
    public static final int MAX_LIMIT     = 1000;
    public static final int DEFAULT_LIMIT = 200;

    /** Un límite ausente o absurdo no revienta: se acota. Nadie debería poder pedir «todo». */
    static int sanitizeLimit(int limit) {
        if (limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    @Transactional(readOnly = true)
    public AuditEntry findById(UUID entryId) {
        return repository.findById(entryId)
                .orElseThrow(() -> new AuditEntryNotFoundException(entryId.toString()));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> findByPartyId(String partyId, Instant from, Instant to, int limit) {
        return repository.findByPartyId(partyId, from, to, sanitizeLimit(limit));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> findByAggregateId(String aggregateId, int limit) {
        return repository.findByAggregateId(aggregateId, sanitizeLimit(limit));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> findByEventType(String eventType, Instant from, Instant to, int limit) {
        return repository.findByEventType(eventType, from, to, sanitizeLimit(limit));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> findByActor(String actor, Instant from, Instant to, int limit) {
        return repository.findByActor(actor, from, to, sanitizeLimit(limit));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> findByActorIp(String actorIp, Instant from, Instant to, int limit) {
        return repository.findByActorIp(actorIp, from, to, sanitizeLimit(limit));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> findByAction(String action, Instant from, Instant to, int limit) {
        return repository.findByAction(action, from, to, sanitizeLimit(limit));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> findByCategory(String category, Instant from, Instant to, int limit) {
        return repository.findByCategory(category, from, to, sanitizeLimit(limit));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> findByDateRange(Instant from, Instant to, int limit) {
        return repository.findByDateRange(from, to, sanitizeLimit(limit));
    }
}
