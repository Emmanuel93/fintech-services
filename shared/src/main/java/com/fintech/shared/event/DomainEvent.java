package com.fintech.shared.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Base para todos los eventos de dominio del sistema.
 *
 * <p>Cada módulo extiende esta clase para sus propios eventos.
 * Spring Modulith externaliza los eventos anotados con {@code @Externalized}
 * al tópico Kafka correspondiente.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>{@code eventId} — inmutable, generado en construcción</li>
 *   <li>{@code occurredOn} — timestamp de creación del evento</li>
 *   <li>{@code correlationId} — trazabilidad end-to-end (puede ser null en eventos root)</li>
 * </ul>
 */
public abstract class DomainEvent {

    private final UUID eventId;
    private final Instant occurredOn;
    private final String correlationId;

    protected DomainEvent(String correlationId) {
        this.eventId = UUID.randomUUID();
        this.occurredOn = Instant.now();
        this.correlationId = correlationId;
    }

    protected DomainEvent() {
        this(null);
    }

    public UUID getEventId() {
        return eventId;
    }

    public Instant getOccurredOn() {
        return occurredOn;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
