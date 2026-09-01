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
 * La cadena del dinero, enhebrada: origen → pago → cuenta → clave de rastreo → línea → póliza.
 *
 * <p><b>Es una proyección, no una fuente de verdad.</b> Cada eslabón sigue siendo dueño de su dato;
 * esto los une para que las cuatro preguntas se contesten con una consulta en vez de con cinco
 * servicios abiertos y un chat.
 *
 * <p>Se completa por tramos, según van llegando los hechos. Que un tramo esté vacío <b>es
 * información</b>: una traza con {@code trackingKey} y sin {@code lineId} es exactamente un pago que
 * salió y que el banco todavía no reporta.
 */
@Entity
@Table(name = "movement_trace", schema = "banking")
public class MovementTrace {

    @Id
    @Column(name = "trace_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "source_system", length = 40)
    private String sourceSystem;

    @Column(name = "source_reference", length = 120)
    private String sourceReference;

    @Column(name = "credit_account_id")
    private UUID creditAccountId;

    @Column(name = "payout_id")
    private UUID payoutId;

    @Column(name = "bank_account_id")
    private UUID bankAccountId;

    @Column(name = "tracking_key", length = 60)
    private String trackingKey;

    @Column(name = "line_id")
    private UUID lineId;

    @Column(name = "voucher_ref", length = 120)
    private String voucherRef;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "business_date")
    private LocalDate businessDate;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "last_event_id", length = 120)
    private String lastEventId;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected MovementTrace() {}

    public static MovementTrace abrir(UUID payoutId, String sourceSystem, String sourceReference,
                                      UUID creditAccountId, BigDecimal amount, String eventId) {
        MovementTrace t = new MovementTrace();
        t.id              = UUID.randomUUID();
        t.payoutId        = payoutId;
        t.sourceSystem    = sourceSystem;
        t.sourceReference = sourceReference;
        t.creditAccountId = creditAccountId;
        t.amount          = amount;
        t.status          = "REQUESTED";
        t.lastEventId     = eventId;
        t.createdAt       = OffsetDateTime.now();
        t.updatedAt       = t.createdAt;
        return t;
    }

    /** Tesorería eligió cuenta: el pago ya sabe por dónde sale. */
    public void ruteado(UUID bankAccountId, String eventId) {
        this.bankAccountId = bankAccountId;
        this.status        = "ROUTED";
        avanzar(eventId);
    }

    /** El conector le asignó clave de rastreo: el pago salió a la red. */
    public void despachado(String trackingKey, LocalDate businessDate, String eventId) {
        this.trackingKey  = trackingKey;
        this.businessDate = businessDate;
        this.status       = "DISPATCHED";
        avanzar(eventId);
    }

    /** El banco lo reporta en el estado de cuenta: el dinero existe fuera de nuestros registros. */
    public void conciliado(UUID lineId, String eventId) {
        this.lineId = lineId;
        this.status = "RECONCILED";
        avanzar(eventId);
    }

    /** El mayor lo asentó. Es el último eslabón: de aquí sale el número que va a un estado financiero. */
    public void asentado(String voucherRef, String eventId) {
        this.voucherRef = voucherRef;
        avanzar(eventId);
    }

    public void devuelto(String eventId) {
        this.status = "RETURNED";
        avanzar(eventId);
    }

    private void avanzar(String eventId) {
        this.lastEventId = eventId;
        this.updatedAt   = OffsetDateTime.now();
    }

    /**
     * La cadena está completa: el dinero salió, el banco lo confirma y el mayor lo asentó.
     *
     * <p>Lo que hace útil esta pregunta no es el sí, es el <b>no</b>: una traza incompleta señala
     * exactamente en qué eslabón se detuvo.
     */
    public boolean estaCompleta() {
        return payoutId != null && bankAccountId != null && trackingKey != null
                && lineId != null && voucherRef != null;
    }

    /** En qué eslabón se detuvo. Nulo si está completa. */
    public String eslabonFaltante() {
        if (bankAccountId == null) return "SIN_RUTEAR";
        if (trackingKey == null)   return "SIN_DESPACHAR";
        if (lineId == null)        return "SIN_CONCILIAR";
        if (voucherRef == null)    return "SIN_ASENTAR";
        return null;
    }

    public UUID getId()               { return id; }
    public String getSourceSystem()   { return sourceSystem; }
    public String getSourceReference(){ return sourceReference; }
    public UUID getCreditAccountId()  { return creditAccountId; }
    public UUID getPayoutId()         { return payoutId; }
    public UUID getBankAccountId()    { return bankAccountId; }
    public String getTrackingKey()    { return trackingKey; }
    public UUID getLineId()           { return lineId; }
    public String getVoucherRef()     { return voucherRef; }
    public BigDecimal getAmount()     { return amount; }
    public LocalDate getBusinessDate(){ return businessDate; }
    public String getStatus()         { return status; }
}
