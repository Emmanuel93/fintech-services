package com.fintech.stp.domain;

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

    private static final Duration BASE_BACKOFF = Duration.ofSeconds(5);
    private static final int MAX_BACKOFF_EXPONENT = 8;   // ~21 min

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
        OutboxMessage message = new OutboxMessage();
        message.outboxId = UUID.randomUUID();
        message.messageType = TYPE_REGISTER_ORDER;
        message.aggregateId = stpPaymentOrderId;
        message.payload = payload;
        message.status = OutboxStatus.PENDING.name();
        message.attemptCount = 0;
        message.nextAttemptAt = Instant.now();
        message.createdAt = Instant.now();
        return message;
    }

    public void markInProgress() { this.status = OutboxStatus.IN_PROGRESS.name(); }

    public void markSent() {
        this.status = OutboxStatus.SENT.name();
        this.sentAt = Instant.now();
    }

    /** Backoff exponencial acotado. No bloquea ningún hilo — el legado hacía Thread.sleep(5 min). */
    public void scheduleRetry(String error) {
        this.attemptCount++;
        this.lastError = error;
        this.status = OutboxStatus.PENDING.name();
        int exponent = Math.min(attemptCount, MAX_BACKOFF_EXPONENT);
        this.nextAttemptAt = Instant.now().plus(BASE_BACKOFF.multipliedBy(1L << (exponent - 1)));
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
