package com.fintech.banking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Lo que la plataforma dice que pasó, proyectado aquí para poder cruzarlo.
 *
 * <p>Banking <b>no conoce el dominio de crédito</b>: recibe un hecho con su referencia opaca, su
 * importe, su fecha y su clave de rastreo. Eso es todo lo que el cruce necesita, y es deliberado —
 * si tuviera que entender qué es una disposición para conciliar, dejaría de ser tesorería.
 */
@Entity
@Table(name = "internal_movements", schema = "banking")
public class InternalMovement {

    @Id
    @Column(name = "internal_movement_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "movement_type", nullable = false, length = 24)
    private String movementType;

    @Column(name = "reference", nullable = false, length = 120)
    private String reference;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "tracking_key", length = 60)
    private String trackingKey;

    @Column(name = "direction", nullable = false, length = 6)
    private String direction;

    /** Idempotencia: el mismo evento reentregado no duplica el candidato. */
    @Column(name = "source_event_id", nullable = false, updatable = false, length = 120)
    private String sourceEventId;

    @Column(name = "projected_at", nullable = false, updatable = false)
    private OffsetDateTime projectedAt;

    protected InternalMovement() {}

    public static InternalMovement de(String type, String reference, BigDecimal amount,
                                      LocalDate businessDate, String trackingKey,
                                      String direction, String sourceEventId) {
        InternalMovement m = new InternalMovement();
        m.id            = UUID.randomUUID();
        m.movementType  = type;
        m.reference     = reference;
        m.amount        = amount;
        m.businessDate  = businessDate;
        m.trackingKey   = trackingKey;
        m.direction     = direction;
        m.sourceEventId = sourceEventId;
        m.projectedAt   = OffsetDateTime.now();
        return m;
    }

    public UUID getId()              { return id; }
    public String getMovementType()  { return movementType; }
    public String getReference()     { return reference; }
    public BigDecimal getAmount()    { return amount; }
    public LocalDate getBusinessDate(){ return businessDate; }
    public String getTrackingKey()   { return trackingKey; }
    public String getDirection()     { return direction; }
    public String getSourceEventId() { return sourceEventId; }
}
