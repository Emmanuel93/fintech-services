package com.fintech.disbursement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Bitácora inmutable de transiciones (DB-06).
 *
 * <p>El legado no tenía nada equivalente: el estado de una dispersión sólo se podía reconstruir
 * leyendo logs. Aquí cada cambio deja una fila, y esa fila es la evidencia que operación y auditoría
 * necesitan sin pedirle nada al proveedor.
 */
@Entity
@Table(schema = "disbursement", name = "disbursement_events")
public class DisbursementEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "disbursement_id", nullable = false, updatable = false)
    private UUID disbursementId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "from_status", updatable = false)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, updatable = false)
    private String toStatus;

    @Column(name = "reason_code", updatable = false)
    private String reasonCode;

    @Column(name = "detail", updatable = false)
    private String detail;

    /** Quién provocó la transición: {@code SYSTEM}, un proveedor, o un usuario del backoffice. */
    @Column(name = "actor", nullable = false, updatable = false)
    private String actor;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected DisbursementEvent() {
    }

    public static DisbursementEvent record(DisbursementOrder order, DisbursementStatus from,
                                           DisbursementStatus to, String reasonCode,
                                           String detail, String actor) {
        DisbursementEvent event = new DisbursementEvent();
        event.eventId = UUID.randomUUID();
        event.disbursementId = order.getDisbursementId();
        event.companyId = order.getCompanyId();
        event.fromStatus = from != null ? from.name() : null;
        event.toStatus = to.name();
        event.reasonCode = reasonCode;
        event.detail = truncate(detail);
        event.actor = actor != null ? actor : "SYSTEM";
        event.occurredAt = Instant.now();
        return event;
    }

    private static String truncate(String value) {
        return value != null && value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    public UUID getEventId() { return eventId; }
    public UUID getDisbursementId() { return disbursementId; }
    public UUID getCompanyId() { return companyId; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getReasonCode() { return reasonCode; }
    public String getDetail() { return detail; }
    public String getActor() { return actor; }
    public Instant getOccurredAt() { return occurredAt; }
}
