package com.fintech.disbursement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Orden de pago saliente. Raíz de agregado.
 *
 * <p><strong>No conoce el dominio de crédito.</strong> La procedencia viaja en {@code sourceSystem},
 * {@code sourceReference} (string opaco) y {@code sourceMetadata} (JSONB), que se devuelven en eco
 * en {@code disbursement.completed} para que el emisor correlacione sin que este servicio entienda
 * nada de lo que hay dentro (DB-09).
 *
 * <p>Inv: DB-01 estado terminal inmutable.
 * <p>Inv: DB-02 {@code (sourceSystem, sourceType, sourceEventId)} es único.
 * <p>Inv: DB-03 {@code SETTLED} sólo con evidencia del proveedor.
 * <p>Inv: DB-06 toda transición escribe en {@code disbursement_events}.
 */
@Entity
@Table(schema = "disbursement", name = "disbursement_orders")
public class DisbursementOrder {

    @Id
    @Column(name = "disbursement_id", nullable = false, updatable = false)
    private UUID disbursementId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /**
     * Bloqueo optimista. Sin esto, el job de despacho y el resultado del proveedor pueden pisarse:
     * el conector responde en milisegundos y la escritura que llega tarde con datos viejos borraría
     * el proveedor y el contador de intentos. Con {@code @Version}, la que pierde revienta y se
     * reintenta con datos frescos.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // ── Procedencia: opaca para el núcleo ─────────────────────────────────────
    @Column(name = "source_system", nullable = false, updatable = false)
    private String sourceSystem;

    @Column(name = "source_type", nullable = false, updatable = false)
    private String sourceType;

    @Column(name = "source_reference", updatable = false)
    private String sourceReference;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private String sourceEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_metadata", nullable = false, updatable = false)
    private Map<String, String> sourceMetadata;

    // ── Instrucción ───────────────────────────────────────────────────────────
    @Embedded
    private Beneficiary beneficiary;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "concept")
    private String concept;

    @Column(name = "numeric_reference")
    private Long numericReference;

    // ── Ejecución ─────────────────────────────────────────────────────────────
    @Column(name = "rail", nullable = false)
    private String rail;

    @Column(name = "provider")
    private String provider;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "external_ref")
    private String externalRef;

    @Column(name = "cep_url")
    private String cepUrl;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "terminated_at")
    private Instant terminatedAt;

    protected DisbursementOrder() {
    }

    public static DisbursementOrder request(UUID companyId, String sourceSystem, DisbursementSource sourceType,
                                            String sourceReference, String sourceEventId,
                                            Map<String, String> sourceMetadata, Beneficiary beneficiary,
                                            BigDecimal amount, String currency, String concept,
                                            Long numericReference, Rail rail, String correlationId) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("El monto del desembolso debe ser positivo");
        }
        DisbursementOrder order = new DisbursementOrder();
        order.disbursementId = UUID.randomUUID();
        order.companyId = companyId;
        order.sourceSystem = sourceSystem;
        order.sourceType = sourceType.name();
        order.sourceReference = sourceReference;
        order.sourceEventId = sourceEventId;
        order.sourceMetadata = sourceMetadata != null ? Map.copyOf(sourceMetadata) : Map.of();
        order.beneficiary = beneficiary;
        order.amount = amount;
        order.currency = currency != null ? currency : "MXN";
        order.concept = concept;
        order.numericReference = numericReference;
        order.rail = rail.name();
        order.status = DisbursementStatus.REQUESTED.name();
        order.attemptCount = 0;
        order.correlationId = correlationId;
        order.createdAt = Instant.now();
        return order;
    }

    /** DB-05: fuera de ventana operativa la orden espera; no se rechaza. */
    public void scheduleFor(Instant moment) {
        requireStatus(DisbursementStatus.REQUESTED, "scheduleFor");
        this.scheduledFor = moment;
    }

    public void dispatch(Provider provider) {
        requireStatus(DisbursementStatus.REQUESTED, "dispatch");
        this.provider = provider.name();
        this.status = DisbursementStatus.DISPATCHED.name();
        this.attemptCount++;
        this.dispatchedAt = Instant.now();
        this.scheduledFor = null;
    }

    /** El proveedor aceptó el registro. NO significa que el dinero haya salido (DB-03). */
    public void accept(String externalRef) {
        requireNotTerminal("accept");
        this.status = DisbursementStatus.ACCEPTED.name();
        this.externalRef = externalRef;
        this.failureCode = null;
        this.failureReason = null;
    }

    /**
     * DB-08: un rechazo transitorio vuelve a la cola, no termina la orden.
     *
     * <p>No incrementa {@code attemptCount} porque {@link #dispatch(Provider)} ya lo hizo: el intento
     * ocurrió, lo que falló fue el resultado.
     */
    public void scheduleRetry(String reasonCode, String reason, Instant nextAttempt) {
        requireNotTerminal("scheduleRetry");
        this.status = DisbursementStatus.REQUESTED.name();
        this.failureCode = reasonCode;
        this.failureReason = reason;
        this.scheduledFor = nextAttempt;
    }

    /**
     * El despacho no llegó a ocurrir — no había regla de routing, o el conector no estaba
     * disponible. Sí gasta intento: si no, una configuración incompleta reintentaría para siempre
     * sin que nadie se entere.
     */
    public void deferAttempt(String reasonCode, String reason, Instant nextAttempt) {
        requireStatus(DisbursementStatus.REQUESTED, "deferAttempt");
        this.attemptCount++;
        this.failureCode = reasonCode;
        this.failureReason = reason;
        this.scheduledFor = nextAttempt;
    }

    public void settle(String externalRef, String cepUrl, Instant settledAt) {
        requireNotTerminal("settle");
        this.status = DisbursementStatus.SETTLED.name();
        this.externalRef = externalRef != null ? externalRef : this.externalRef;
        this.cepUrl = cepUrl;
        this.settledAt = settledAt != null ? settledAt : Instant.now();
        this.terminatedAt = Instant.now();
    }

    public void reject(String failureCode, String failureReason) {
        terminate(DisbursementStatus.REJECTED, failureCode, failureReason);
    }

    /**
     * Única transición legítima desde un estado terminal: el dinero salió, llegó, y el banco
     * receptor lo devolvió después. Negarla dejaría la orden mintiendo en {@code SETTLED}.
     */
    public void markReturned(String causeCode) {
        DisbursementStatus current = status();
        if (current == DisbursementStatus.RETURNED) {
            return;                                   // idempotente: la devolución ya se aplicó
        }
        if (current.isTerminal() && current != DisbursementStatus.SETTLED) {
            throw new InvalidDisbursementStateException(
                    "No se puede devolver una orden en " + current + " (disbursementId=" + disbursementId + ")");
        }
        this.status = DisbursementStatus.RETURNED.name();
        this.failureCode = FailureCode.RETURNED_BY_BENEFICIARY_BANK.name();
        this.failureReason = causeCode;
        this.terminatedAt = Instant.now();
    }

    public void fail(String failureCode, String failureReason) {
        terminate(DisbursementStatus.FAILED, failureCode, failureReason);
    }

    public void cancel(String reason) {
        requireStatus(DisbursementStatus.REQUESTED, "cancel");
        terminate(DisbursementStatus.CANCELLED, "CANCELLED_BY_OPERATOR", reason);
    }

    private void terminate(DisbursementStatus target, String failureCode, String failureReason) {
        requireNotTerminal(target.name().toLowerCase());
        this.status = target.name();
        this.failureCode = failureCode;
        this.failureReason = failureReason;
        this.terminatedAt = Instant.now();
    }

    public DisbursementStatus status() { return DisbursementStatus.valueOf(status); }
    public boolean isTerminal() { return status().isTerminal(); }
    public DisbursementSource source() { return DisbursementSource.valueOf(sourceType); }
    public Rail railValue() { return Rail.valueOf(rail); }

    private void requireNotTerminal(String operation) {
        if (isTerminal()) {
            throw new InvalidDisbursementStateException(
                    "No se puede ejecutar '" + operation + "' sobre una orden terminal " + status
                            + " (disbursementId=" + disbursementId + ")");
        }
    }

    private void requireStatus(DisbursementStatus expected, String operation) {
        if (status() != expected) {
            throw new InvalidDisbursementStateException(
                    "'" + operation + "' exige estado " + expected + " pero la orden está en " + status);
        }
    }

    public UUID getDisbursementId() { return disbursementId; }
    public UUID getCompanyId() { return companyId; }
    public String getSourceSystem() { return sourceSystem; }
    public String getSourceType() { return sourceType; }
    public String getSourceReference() { return sourceReference; }
    public String getSourceEventId() { return sourceEventId; }
    public Map<String, String> getSourceMetadata() { return sourceMetadata; }
    public Beneficiary getBeneficiary() { return beneficiary; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getConcept() { return concept; }
    public Long getNumericReference() { return numericReference; }
    public String getRail() { return rail; }
    public String getProvider() { return provider; }
    public String getStatus() { return status; }
    public String getExternalRef() { return externalRef; }
    public String getCepUrl() { return cepUrl; }
    public String getFailureCode() { return failureCode; }
    public String getFailureReason() { return failureReason; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getScheduledFor() { return scheduledFor; }
    public String getCorrelationId() { return correlationId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public Instant getSettledAt() { return settledAt; }
    public Instant getTerminatedAt() { return terminatedAt; }
    public long getVersion() { return version; }
}
