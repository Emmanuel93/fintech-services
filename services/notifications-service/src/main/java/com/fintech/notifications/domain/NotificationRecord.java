package com.fintech.notifications.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Un registro por (evento, canal) intentado — un dispatch SIMULTANEOUS genera varios registros.
 * Idempotente por (sourceEventId, channel): un replay de Kafka no duplica el envío.
 *
 * <p>{@code recipientId} es {@code obligorPartyId} para las 5 notificaciones post-activación, pero
 * es {@code prospectId} para OFFER_PRESENTED (#1) — en ese punto del ciclo de vida todavía no existe
 * un Party correlacionable (ver ContactResolutionService). No se llama {@code partyId} para no
 * insinuar que siempre es un Party real.
 */
@Entity
@Table(name = "notification_records", schema = "notifications")
public class NotificationRecord {

    @Id
    @Column(name = "notification_id", nullable = false, updatable = false)
    private UUID notificationId;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private String sourceEventId;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    /**
     * Qué clase de entidad es el destinatario. Texto libre: este servicio no lo interpreta.
     *
     * <p>Existe para poder consultar el buzón de una entidad sin confundirla con otra que comparta
     * id, no para que el notificador decida nada distinto según su valor.
     */
    @Column(name = "recipient_type", nullable = false, updatable = false, length = 40)
    private String recipientType;

    /**
     * La clave del evento, como texto.
     *
     * <p>Sustituye a {@code eventType} como llave de plantillas y políticas. Con un enum, cada
     * emisor nuevo obligaba a recompilar y redesplegar este servicio para agregar su caso — el
     * acoplamiento más caro de todos, porque volvía «mandar un aviso» un cambio de código ajeno.
     */
    @Column(name = "event_key", nullable = false, updatable = false, length = 80)
    private String eventKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    /** NULL = no leída (inbox in-app de fa_notifications). */
    @Column(name = "read_at")
    private Instant readAt;

    /** El tipo de lo ya escrito antes de que el destinatario fuera abstracto. */
    public static final String PARTY = "PARTY";

    protected NotificationRecord() {}

    /** Camino del journey de crédito: destinatario de tipo {@code PARTY} y clave del enum. */
    public static NotificationRecord sent(String sourceEventId, UUID recipientId, EventType eventType,
                                            NotificationChannel channel) {
        return build(sourceEventId, PARTY, recipientId, eventType, eventType.name(),
                channel, NotificationStatus.SENT, null);
    }

    public static NotificationRecord failed(String sourceEventId, UUID recipientId, EventType eventType,
                                              NotificationChannel channel, String reason) {
        return build(sourceEventId, PARTY, recipientId, eventType, eventType.name(),
                channel, NotificationStatus.FAILED, reason);
    }

    /** Camino genérico: cualquier entidad, cualquier clave de evento. */
    public static NotificationRecord sentTo(String sourceEventId, String recipientType, UUID recipientId,
                                             String eventKey, NotificationChannel channel) {
        return build(sourceEventId, recipientType, recipientId, enumOrNull(eventKey), eventKey,
                channel, NotificationStatus.SENT, null);
    }

    public static NotificationRecord failedTo(String sourceEventId, String recipientType, UUID recipientId,
                                               String eventKey, NotificationChannel channel, String reason) {
        return build(sourceEventId, recipientType, recipientId, enumOrNull(eventKey), eventKey,
                channel, NotificationStatus.FAILED, reason);
    }

    /**
     * Rellena {@code eventType} sólo si la clave coincide con el catálogo del journey de crédito.
     *
     * <p>Una clave de otro emisor deja la columna nula, y así debe ser: inventarle un enum sería
     * reabrir la puerta que este modelo cierra.
     */
    private static EventType enumOrNull(String eventKey) {
        if (eventKey == null) return null;
        try {
            return EventType.valueOf(eventKey);
        } catch (IllegalArgumentException notInCatalog) {
            return null;
        }
    }

    private static NotificationRecord build(String sourceEventId, String recipientType, UUID recipientId,
                                               EventType eventType, String eventKey,
                                               NotificationChannel channel, NotificationStatus status,
                                               String failureReason) {
        NotificationRecord r = new NotificationRecord();
        r.notificationId = UUID.randomUUID();
        r.sourceEventId   = sourceEventId;
        r.recipientType    = recipientType == null ? PARTY : recipientType.trim().toUpperCase();
        r.recipientId      = recipientId;
        r.eventType         = eventType;
        r.eventKey           = eventKey;
        r.channel            = channel;
        r.status              = status;
        r.failureReason        = failureReason;
        r.sentAt                = Instant.now();
        return r;
    }

    /** Idempotente — marcar dos veces no cambia el instante original de lectura. */
    public void markRead() {
        if (this.readAt == null) {
            this.readAt = Instant.now();
        }
    }

    public boolean isRead() { return readAt != null; }

    public UUID getNotificationId()          { return notificationId; }
    public String getSourceEventId()          { return sourceEventId; }
    public String getRecipientType() { return recipientType; }
    public String getEventKey()      { return eventKey; }
    public UUID getRecipientId()               { return recipientId; }
    public EventType getEventType()             { return eventType; }
    public NotificationChannel getChannel()      { return channel; }
    public NotificationStatus getStatus()         { return status; }
    public String getFailureReason()               { return failureReason; }
    public Instant getSentAt()                      { return sentAt; }
    public Instant getReadAt()                      { return readAt; }
}
