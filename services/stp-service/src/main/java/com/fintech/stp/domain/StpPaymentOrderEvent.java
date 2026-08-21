package com.fintech.stp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Bitácora append-only de la orden. Traza regulatoria: nunca se actualiza ni se borra.
 * Es el equivalente honesto de la {@code his_bitacoratransaccionesstp} del legado.
 */
@Entity
@Table(schema = "stp", name = "payment_order_events")
public class StpPaymentOrderEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "stp_payment_order_id", nullable = false, updatable = false)
    private UUID stpPaymentOrderId;

    @Column(name = "from_status", updatable = false)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, updatable = false)
    private String toStatus;

    @Column(name = "reason_code", updatable = false)
    private String reasonCode;

    @Column(name = "detail", updatable = false)
    private String detail;

    /** SYSTEM · PROVIDER · el userId de quien lo hizo desde el backoffice. */
    @Column(name = "actor", nullable = false, updatable = false)
    private String actor;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected StpPaymentOrderEvent() {
    }

    public static StpPaymentOrderEvent of(UUID stpPaymentOrderId, StpPaymentOrderStatus fromStatus,
                                          StpPaymentOrderStatus toStatus, String reasonCode,
                                          String detail, String actor) {
        StpPaymentOrderEvent event = new StpPaymentOrderEvent();
        event.eventId = UUID.randomUUID();
        event.stpPaymentOrderId = stpPaymentOrderId;
        event.fromStatus = fromStatus != null ? fromStatus.name() : null;
        event.toStatus = toStatus.name();
        event.reasonCode = reasonCode;
        event.detail = detail;
        event.actor = actor;
        event.occurredAt = Instant.now();
        return event;
    }

    public UUID getEventId() { return eventId; }
    public UUID getStpPaymentOrderId() { return stpPaymentOrderId; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getReasonCode() { return reasonCode; }
    public String getDetail() { return detail; }
    public String getActor() { return actor; }
    public Instant getOccurredAt() { return occurredAt; }
}
