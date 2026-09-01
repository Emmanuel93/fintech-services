package com.fintech.creditportfolio.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dispositions", schema = "credit_portfolio")
public class Disposition {

    @Id
    @Column(name = "disposition_id", nullable = false, updatable = false)
    private UUID dispositionId;

    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposition_type", nullable = false, updatable = false)
    private DispositionType dispositionType;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    /** Beneficiary — only for THIRD_PARTY_CREDIT (DE-02). */
    @Column(name = "beneficiary_party_id")
    private UUID beneficiaryPartyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DispositionStatus status;

    /** SPEI/external reference number after COMPLETED. */
    @Column(name = "external_ref")
    private String externalRef;

    /** Idempotency key for wallet-initiated dispositions — null for the origination-time disposition. */
    @Column(name = "source_event_id")
    private String sourceEventId;

    /**
     * Si tiene calendario propio o se exige entera en el corte.
     *
     * <p>Lo decide el producto, no la petición: {@code AT_DISPOSITION} nace amortizada,
     * {@code POST_HOC} nace revolvente y el titular la difiere después.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "plan_mode", nullable = false)
    private DispositionPlanMode planMode;

    /** Cuándo el titular difirió esta compra. Nulo mientras siga revolvente o si nació amortizada. */
    @Column(name = "deferred_at")
    private Instant deferredAt;

    @Column(name = "deferred_term")
    private Integer deferredTerm;

    /** En qué ciclo de corte se facturó. Nulo = todavía no entró a ningún exigible. */
    @Column(name = "billed_cycle")
    private Integer billedCycle;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Disposition() {}

    public static Disposition create(UUID creditAccountId, DispositionType type,
                                      BigDecimal amount, UUID beneficiaryPartyId) {
        return create(creditAccountId, type, amount, beneficiaryPartyId, null);
    }

    public static Disposition create(UUID creditAccountId, DispositionType type,
                                      BigDecimal amount, UUID beneficiaryPartyId,
                                      String sourceEventId) {
        Disposition d = new Disposition();
        d.dispositionId    = UUID.randomUUID();
        d.creditAccountId  = creditAccountId;
        d.dispositionType  = type;
        d.amount           = amount;
        d.beneficiaryPartyId = beneficiaryPartyId;
        d.status           = DispositionStatus.PENDING;
        d.sourceEventId    = sourceEventId;
        // AMORTIZED por defecto: es lo que era TODA disposición hasta BK-24, y lo que sigue siendo
        // la colocación de un distribuidor. Sólo un producto POST_HOC la marca revolvente.
        d.planMode         = DispositionPlanMode.AMORTIZED;
        d.createdAt        = Instant.now();
        return d;
    }

    /**
     * Una compra que nace <b>revolvente pura</b>: sin calendario, exigible entera en el corte.
     *
     * <p>Es la mecánica de una tarjeta. El titular puede diferirla después con {@link #diferir},
     * antes de que corte el ciclo.
     */
    public Disposition comoRevolventePura() {
        this.planMode = DispositionPlanMode.REVOLVING;
        return this;
    }

    /**
     * El titular difiere la compra: deja de ser exigible entera en el corte y pasa a tener plan.
     *
     * <p>Idempotente por diseño — diferir dos veces la misma compra no la parte en dos planes.
     * Quien intenta re-diferir una ya diferida se encuentra el rechazo aquí y no un segundo
     * calendario colgando de la misma disposición.
     */
    public void diferir(int plazo) {
        if (planMode == DispositionPlanMode.AMORTIZED) {
            throw new IllegalStateException(
                    "La disposición " + dispositionId + " ya tiene plan: no se difiere dos veces");
        }
        this.planMode     = DispositionPlanMode.AMORTIZED;
        this.deferredAt   = Instant.now();
        this.deferredTerm = plazo;
    }

    /** Exigible entera en el próximo corte: nació revolvente, sigue sin diferirse y sin facturar. */
    public boolean esExigibleEnElCorte() {
        return planMode == DispositionPlanMode.REVOLVING && billedCycle == null;
    }

    /**
     * Entra al exigible de este ciclo.
     *
     * <p>Sin la marca, el corte siguiente volvería a exigir las mismas compras: {@code planMode}
     * sigue siendo {@code REVOLVING} después de facturarla.
     */
    public void facturarEnCiclo(int ciclo) {
        if (billedCycle != null) {
            throw new IllegalStateException("La disposición " + dispositionId
                    + " ya se facturó en el ciclo " + billedCycle);
        }
        this.billedCycle = ciclo;
    }

    public void markProcessing() {
        this.status = DispositionStatus.PROCESSING;
    }

    public void complete(String externalRef) {
        this.status      = DispositionStatus.COMPLETED;
        this.externalRef = externalRef;
        this.completedAt = Instant.now();
    }

    public void fail() {
        this.status = DispositionStatus.FAILED;
    }

    /**
     * El banco receptor devolvió el dinero.
     *
     * <p>{@code REVERSED} y no {@code FAILED} porque son hechos distintos y se explican distinto al
     * cliente: en un fallo el pago nunca salió; en una devolución salió, llegó y volvió. La cuenta
     * queda igual en los dos casos, pero el expediente no.
     */
    public void reverse() {
        this.status = DispositionStatus.REVERSED;
    }

    public UUID getDispositionId()      { return dispositionId; }
    public UUID getCreditAccountId()    { return creditAccountId; }
    public DispositionType getDispositionType() { return dispositionType; }
    public BigDecimal getAmount()       { return amount; }
    public UUID getBeneficiaryPartyId() { return beneficiaryPartyId; }
    public DispositionStatus getStatus(){ return status; }
    public String getExternalRef()      { return externalRef; }
    public String getSourceEventId()    { return sourceEventId; }
    public DispositionPlanMode getPlanMode() { return planMode; }
    public Instant getDeferredAt()      { return deferredAt; }
    public Integer getDeferredTerm()    { return deferredTerm; }
    public Integer getBilledCycle()     { return billedCycle; }
    public Instant getCreatedAt()       { return createdAt; }
    public Instant getCompletedAt()     { return completedAt; }
}
