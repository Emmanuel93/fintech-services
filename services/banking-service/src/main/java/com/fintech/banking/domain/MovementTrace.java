package com.fintech.banking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

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

    /**
     * Cada tramo llega por un topic distinto, y dos pueden tocar la misma traza a la vez. Sin
     * versión, el que confirma segundo reescribe la fila con lo que leyó antes y borra el tramo del
     * otro; con ella, pierde y se reintenta sobre lo que ya hay.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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

    /** Why the payout ended without the money leaving; null unless FAILED. */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // Cuándo llegó cada tramo. Que un tramo exista dice que pasó; cuándo, dice dónde buscar.
    @Column(name = "routed_at")
    private OffsetDateTime routedAt;

    @Column(name = "dispatched_at")
    private OffsetDateTime dispatchedAt;

    /** La liquidación que confirma el conector. Informativa: no es un eslabón de {@link #isComplete()}. */
    @Column(name = "settled_at")
    private OffsetDateTime settledAt;

    @Column(name = "reconciled_at")
    private OffsetDateTime reconciledAt;

    @Column(name = "posted_at")
    private OffsetDateTime postedAt;

    protected MovementTrace() {}

    public static MovementTrace open(UUID payoutId, String sourceSystem, String sourceReference,
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

    /**
     * Completa el origen con lo que el tramo actual sabe y el que abrió la traza no.
     *
     * <p>La traza la puede abrir el despacho al conector, que habla vocabulario de pagos y no sabe
     * de qué crédito viene. Cuando llega el hecho que sí lo sabe, se rellena lo que falta —<b>nunca
     * se pisa</b> lo que ya estaba—: el primero que lo dijo es el que manda.
     */
    public void describeOrigin(String sourceSystem, String sourceReference,
                               UUID creditAccountId, BigDecimal amount) {
        if (this.sourceSystem == null)    this.sourceSystem    = sourceSystem;
        if (this.sourceReference == null) this.sourceReference = sourceReference;
        if (this.creditAccountId == null) this.creditAccountId = creditAccountId;
        if (this.amount == null)          this.amount          = amount;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Tesorería eligió cuenta: el pago ya sabe por dónde sale.
     *
     * <p>Un reintento antes de salir a la red puede re-rutear, y gana la última cuenta. Ya
     * despachado, no: un despacho reentregado tarde no puede regresar la traza a {@code ROUTED} ni
     * cambiar la cuenta por la que el dinero ya salió.
     */
    public void routed(UUID bankAccountId, String eventId) {
        if (trackingKey != null && this.bankAccountId != null) {
            return;
        }
        this.bankAccountId = bankAccountId;
        this.routedAt      = OffsetDateTime.now();
        if (trackingKey == null) {
            this.status = "ROUTED";
        }
        advance(eventId);
    }

    /**
     * El conector le asignó clave de rastreo: el pago salió a la red.
     *
     * <p>Lo pueden decir dos hechos —la aceptación del proveedor y su liquidación— y el segundo
     * sólo completa lo que el primero no traía (la fecha de operación). La clave de una orden no
     * cambia, así que la primera que llega es la buena.
     */
    public void dispatched(String trackingKey, LocalDate businessDate, String eventId) {
        if (this.trackingKey != null) {
            if (this.businessDate == null && businessDate != null) {
                this.businessDate = businessDate;
                advance(eventId);
            }
            return;
        }
        this.trackingKey  = trackingKey;
        this.businessDate = businessDate;
        this.dispatchedAt = OffsetDateTime.now();
        if (!isPastDispatch()) {
            this.status = "DISPATCHED";
        }
        advance(eventId);
    }

    /**
     * El conector confirmó la liquidación. No cierra la cadena —eso lo hace el estado de cuenta—,
     * pero separa «el banco aún no lo reporta» de «el dinero aún no llega».
     */
    public void settled(OffsetDateTime when, String eventId) {
        if (this.settledAt != null) {
            return;
        }
        this.settledAt = when != null ? when : OffsetDateTime.now();
        if (!"RECONCILED".equals(status) && !"RETURNED".equals(status)) {
            this.status = "SETTLED";
        }
        advance(eventId);
    }

    /** El banco lo reporta en el estado de cuenta: el dinero existe fuera de nuestros registros. */
    public void reconciled(UUID lineId, String eventId) {
        this.lineId       = lineId;
        this.reconciledAt = OffsetDateTime.now();
        this.status       = "RECONCILED";
        advance(eventId);
    }

    /** El mayor lo asentó. Es el último eslabón: de aquí sale el número que va a un estado financiero. */
    public void posted(String voucherRef, String eventId) {
        this.voucherRef = voucherRef;
        this.postedAt   = OffsetDateTime.now();
        advance(eventId);
    }

    private boolean isPastDispatch() {
        return "SETTLED".equals(status) || "RECONCILED".equals(status) || "RETURNED".equals(status);
    }

    public void returned(String eventId) {
        this.status = "RETURNED";
        advance(eventId);
    }

    /**
     * The payout ended without the money leaving: the bank rejected it, or it never reached the bank. Terminal, with
     * its reason. Before, nothing told the trace, and a rejected payout stayed dispatched for good (D61).
     */
    public void failed(String reason, String eventId) {
        if (isPastDispatch()) {
            return;   // the money left: a late failure does not undo a settlement
        }
        this.status = "FAILED";
        this.failureReason = reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
        advance(eventId);
    }

    public String getFailureReason() { return failureReason; }

    private void advance(String eventId) {
        this.lastEventId = eventId;
        this.updatedAt   = OffsetDateTime.now();
    }

    /**
     * La cadena está completa: el dinero salió, el banco lo confirma y el mayor lo asentó.
     *
     * <p>Lo que hace útil esta pregunta no es el sí, es el <b>no</b>: una traza incompleta señala
     * exactamente en qué eslabón se detuvo.
     */
    public boolean isComplete() {
        return payoutId != null && bankAccountId != null && trackingKey != null
                && lineId != null && voucherRef != null;
    }

    /** En qué eslabón se detuvo. Nulo si está completa. */
    public String missingLink() {
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
    public OffsetDateTime getCreatedAt()    { return createdAt; }
    public OffsetDateTime getRoutedAt()     { return routedAt; }
    public OffsetDateTime getDispatchedAt() { return dispatchedAt; }
    public OffsetDateTime getSettledAt()    { return settledAt; }
    public OffsetDateTime getReconciledAt() { return reconciledAt; }
    public OffsetDateTime getPostedAt()     { return postedAt; }
}
