package com.fintech.stp.domain;

import com.fintech.shared.time.BusinessClock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbox transaccional. Existe para que <strong>nunca</strong> se llame a STP dentro de la
 * transacción que persiste la orden.
 *
 * <p>El legado resolvía la misma carrera publicando todos los mensajes con dos minutos de retardo
 * programado, un hack sobre una anotación AMQP. Con outbox no hace falta.
 */
@Entity
@Table(schema = "stp", name = "outbox_messages")
public class OutboxMessage {

    public static final String TYPE_REGISTER_ORDER = "REGISTER_ORDER";
    /** An integration event to publish once the transaction that wrote it commits. */
    public static final String TYPE_PUBLISH_EVENT = "PUBLISH_EVENT";

    private static final int MAX_BACKOFF_EXPONENT = 8;   // 128 times the base: under 11 min with the default 5 s

    @Id
    @Column(name = "outbox_id", nullable = false, updatable = false)
    private UUID outboxId;

    @Column(name = "message_type", nullable = false, updatable = false)
    private String messageType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboxMessage() {
    }

    public static OutboxMessage registerOrder(UUID stpPaymentOrderId, String payload) {
        return registerOrder(stpPaymentOrderId, payload, BusinessClock.current().now());
    }

    /**
     * An order that may not be sent before {@code notBefore}: the end of the hold around the SPEI cutoff, while STP
     * changes the operation date. Sent then, it takes the next business day, which is the date it was given.
     */
    public static OutboxMessage registerOrder(UUID stpPaymentOrderId, String payload, Instant notBefore) {
        Instant now = BusinessClock.current().now();
        OutboxMessage message = new OutboxMessage();
        message.outboxId = UUID.randomUUID();
        message.messageType = TYPE_REGISTER_ORDER;
        message.aggregateId = stpPaymentOrderId;
        message.payload = payload;
        message.status = OutboxStatus.PENDING.name();
        message.attemptCount = 0;
        message.nextAttemptAt = notBefore != null && notBefore.isAfter(now) ? notBefore : now;
        message.createdAt = now;
        return message;
    }

    /** An event about {@code aggregateId}; {@code envelope} carries its topic, key and body. */
    public static OutboxMessage publishEvent(UUID aggregateId, String envelope) {
        Instant now = BusinessClock.current().now();
        OutboxMessage message = new OutboxMessage();
        message.outboxId = UUID.randomUUID();
        message.messageType = TYPE_PUBLISH_EVENT;
        message.aggregateId = aggregateId;
        message.payload = envelope;
        message.status = OutboxStatus.PENDING.name();
        message.attemptCount = 0;
        message.nextAttemptAt = now;
        message.createdAt = now;
        return message;
    }

    public void markInProgress() { this.status = OutboxStatus.IN_PROGRESS.name(); }

    public void markSent() {
        this.status = OutboxStatus.SENT.name();
        this.sentAt = BusinessClock.current().now();
    }

    /**
     * Backoff exponencial acotado. No bloquea ningún hilo — el legado hacía Thread.sleep(5 min).
     *
     * @param baseBackoff the wait before the first retry; each later one doubles it. Zero retries at once: the
     *                    attempts then all happen at the moment the order was registered (the local stack's stub)
     */
    public void scheduleRetry(String error, Duration baseBackoff) {
        this.attemptCount++;
        this.lastError = error;
        this.status = OutboxStatus.PENDING.name();
        int exponent = Math.min(attemptCount, MAX_BACKOFF_EXPONENT);
        this.nextAttemptAt = BusinessClock.current().now().plus(baseBackoff.multipliedBy(1L << (exponent - 1)));
    }

    public void markFailed(String error) {
        this.status = OutboxStatus.FAILED.name();
        this.lastError = error;
    }

    public UUID getOutboxId() { return outboxId; }
    public String getMessageType() { return messageType; }
    public UUID getAggregateId() { return aggregateId; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
}
