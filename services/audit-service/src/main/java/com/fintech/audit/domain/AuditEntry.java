package com.fintech.audit.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "audit", name = "audit_entries")
public class AuditEntry {

    /** Distingue un hecho de dominio consumido por Kafka de un acceso (alguien consultó/descargó algo). */
    public static final String CATEGORY_DOMAIN_EVENT = "DOMAIN_EVENT";
    public static final String CATEGORY_ACCESS       = "ACCESS";

    @Id
    private UUID entryId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String domainSource;

    private String aggregateId;
    private String partyId;
    private String correlationId;

    /** Quién causó el evento: staffUserId del empleado, "SYSTEM", o el username/sujeto. Puede ser null. */
    private String actor;

    /**
     * El "quién" legible, congelado al momento del acceso.
     *
     * <p>Snapshot deliberado: si el correo o el teléfono cambian después, la entrada conserva los de
     * entonces. Una bitácora que siguiera al dato vivo reescribiría el pasado cada vez que alguien
     * actualiza su perfil y dejaría de servir como prueba de lo que se sabía en ese momento.
     */
    private String actorEmail;
    private String actorName;
    private String actorCurp;
    private String actorPhone;

    /**
     * El "sobre quién": la identidad de la persona sobre la que se actuó, congelada igual.
     *
     * <p>Su identificador es {@link #partyId}, que ya existía; aquí va sólo lo que hace falta para
     * leerla sin ir a buscarla a otro servicio. Quién actuó y sobre quién son preguntas distintas y
     * la bitácora sólo sabía responder la primera.
     */
    private String subjectName;
    private String subjectCurp;
    private String subjectEmail;
    private String subjectPhone;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    // ── Contexto de acceso (categoría ACCESS): el "quién / sobre qué / desde dónde / cuándo" ─────
    @Column(nullable = false)
    private String category;              // DOMAIN_EVENT | ACCESS
    private String action;                // VIEW | SEARCH | DOWNLOAD | EXPORT | READ | MUTATION | LOGIN
    private String actorRoles;            // roles vigentes del actor
    private String actorChannel;          // BACKOFFICE | MOBILE | ...
    @Column(name = "actor_ip")
    private String actorIp;               // IP del cliente
    private String userAgent;
    private String sessionId;
    private String resourceType;          // sobre QUÉ (pantalla/recurso)
    private String resourceId;            // id concreto consultado
    private String httpMethod;
    private String httpPath;
    private String httpQuery;
    private String outcome;               // SUCCESS | DENIED | ERROR
    private Integer statusCode;
    private Long durationMs;
    private Instant occurredAt;           // cuándo ocurrió (reloj del canal); createdAt es el sello de registro

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditEntry() {}

    public static AuditEntry create(String eventType, String domainSource,
                                    String aggregateId, String partyId,
                                    String correlationId, String payload) {
        return create(eventType, domainSource, aggregateId, partyId, correlationId, null, payload);
    }

    public static AuditEntry create(String eventType, String domainSource,
                                    String aggregateId, String partyId,
                                    String correlationId, String actor, String payload) {
        var e = base(eventType, domainSource, aggregateId, partyId, correlationId, actor, payload);
        e.category = CATEGORY_DOMAIN_EVENT;
        return e;
    }

    /**
     * Quién actuó, identificado plenamente. Se agrupa porque es un solo hecho —la persona detrás del
     * identificador— y porque desperdigado en siete parámetros sueltos era cuestión de tiempo que
     * alguien cruzara el correo con el nombre en una llamada de veintitantos argumentos.
     */
    public record ActorIdentity(String actor, String email, String name, String curp, String phone,
                                String roles, String channel) {

        public static final ActorIdentity DESCONOCIDO =
                new ActorIdentity(null, null, null, null, null, null, null);
    }

    /** Sobre quién se actuó. {@code partyId} es el id con que el resto del sistema lo conoce. */
    public record SubjectIdentity(String partyId, String name, String curp,
                                  String email, String phone) {

        public static final SubjectIdentity NINGUNO =
                new SubjectIdentity(null, null, null, null, null);
    }

    /**
     * Entrada de ACCESO: alguien entró a una pantalla, buscó, descargó o consumió información.
     * Registra el quién completo (identidad, roles, canal, IP, user-agent, sesión), sobre qué
     * (recurso + id + ruta HTTP), sobre quién y cuándo (occurredAt del canal + createdAt de registro).
     *
     * <p>El {@code domainSource} lo pone el canal que reporta. Iba fijo a
     * {@code "channel-backoffice-service"} para toda entrada de acceso viniera de donde viniera, con
     * lo que los accesos desde la app quedaban atribuidos al backoffice: la bitácora afirmaba que un
     * cliente había entrado por una consola a la que no tiene acceso.
     */
    public static AuditEntry access(String action, ActorIdentity actor, SubjectIdentity subject,
                                    String actorIp, String userAgent, String sessionId,
                                    String resourceType, String resourceId,
                                    String httpMethod, String httpPath, String httpQuery,
                                    String outcome, Integer statusCode, Long durationMs,
                                    String correlationId, String domainSource,
                                    Instant occurredAt, String payload) {
        ActorIdentity quien = actor != null ? actor : ActorIdentity.DESCONOCIDO;
        SubjectIdentity sobreQuien = subject != null ? subject : SubjectIdentity.NINGUNO;

        String eventType = "ACCESS_" + (action != null ? action : "READ");
        var e = base(eventType,
                domainSource != null && !domainSource.isBlank() ? domainSource : "channel",
                resourceId, sobreQuien.partyId(), correlationId, quien.actor(),
                payload != null ? payload : "{}");
        e.category      = CATEGORY_ACCESS;
        e.action        = action;
        e.actorEmail    = quien.email();
        e.actorName     = quien.name();
        e.actorCurp     = quien.curp();
        e.actorPhone    = quien.phone();
        e.subjectName   = sobreQuien.name();
        e.subjectCurp   = sobreQuien.curp();
        e.subjectEmail  = sobreQuien.email();
        e.subjectPhone  = sobreQuien.phone();
        e.actorRoles    = quien.roles();
        e.actorChannel  = quien.channel();
        e.actorIp       = actorIp;
        e.userAgent     = userAgent;
        e.sessionId     = sessionId;
        e.resourceType  = resourceType;
        e.resourceId    = resourceId;
        e.httpMethod    = httpMethod;
        e.httpPath      = httpPath;
        e.httpQuery     = httpQuery;
        e.outcome       = outcome;
        e.statusCode    = statusCode;
        e.durationMs    = durationMs;
        e.occurredAt    = occurredAt != null ? occurredAt : e.createdAt;
        return e;
    }

    private static AuditEntry base(String eventType, String domainSource, String aggregateId,
                                   String partyId, String correlationId, String actor, String payload) {
        var e = new AuditEntry();
        e.entryId       = UUID.randomUUID();
        e.eventType     = eventType;
        e.domainSource  = domainSource;
        e.aggregateId   = aggregateId;
        e.partyId       = partyId;
        e.correlationId = correlationId;
        e.actor         = actor;
        e.payload       = payload;
        e.createdAt     = Instant.now();
        // Toda entrada lleva un "cuándo" con hora: por defecto el sello de registro. Un acceso lo
        // sobreescribe con el instante real del canal. Nunca queda null → la hora siempre se refleja.
        e.occurredAt    = e.createdAt;
        return e;
    }

    public UUID getEntryId()        { return entryId; }
    public String getEventType()    { return eventType; }
    public String getDomainSource() { return domainSource; }
    public String getAggregateId()  { return aggregateId; }
    public String getPartyId()      { return partyId; }
    public String getCorrelationId(){ return correlationId; }
    public String getActor()        { return actor; }
    public String getActorEmail()   { return actorEmail; }
    public String getActorName()    { return actorName; }
    public String getActorCurp()    { return actorCurp; }
    public String getActorPhone()   { return actorPhone; }
    public String getSubjectName()  { return subjectName; }
    public String getSubjectCurp()  { return subjectCurp; }
    public String getSubjectEmail() { return subjectEmail; }
    public String getSubjectPhone() { return subjectPhone; }
    public String getPayload()      { return payload; }
    public String getCategory()     { return category; }
    public String getAction()       { return action; }
    public String getActorRoles()   { return actorRoles; }
    public String getActorChannel() { return actorChannel; }
    public String getActorIp()      { return actorIp; }
    public String getUserAgent()    { return userAgent; }
    public String getSessionId()    { return sessionId; }
    public String getResourceType() { return resourceType; }
    public String getResourceId()   { return resourceId; }
    public String getHttpMethod()   { return httpMethod; }
    public String getHttpPath()     { return httpPath; }
    public String getHttpQuery()    { return httpQuery; }
    public String getOutcome()      { return outcome; }
    public Integer getStatusCode()  { return statusCode; }
    public Long getDurationMs()     { return durationMs; }
    public Instant getOccurredAt()  { return occurredAt; }
    public Instant getCreatedAt()   { return createdAt; }
}
