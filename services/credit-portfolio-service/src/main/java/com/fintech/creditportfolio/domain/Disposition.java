package com.fintech.creditportfolio.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

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

    // ── Qué se sabía de listas cuando salió el dinero ────────────────────────────────────────
    //
    // Es una FOTO, y su valor está en que no cambie cuando cambie el veredicto vigente. El negocio
    // asumió la ventana entre que se activa una lista y que termina el re-cruce; esto es lo que
    // permite distinguir después «no lo sabíamos» de «lo dejamos pasar».

    @Column(name = "aml_screening_status", length = 20)
    private String amlScreeningStatus;

    @Column(name = "aml_list_versions", length = 1000)
    private String amlListVersions;

    @Column(name = "aml_verdict_at")
    private Instant amlVerdictAt;

    @Column(name = "aml_checked_at")
    private Instant amlCheckedAt;

    /** En qué ciclo de corte se facturó. Nulo = todavía no entró a ningún exigible. */
    @Column(name = "billed_cycle")
    private Integer billedCycle;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** The attempt of its payment in flight, or the last one: each is its own disbursement order (D63). */
    @Column(nullable = false)
    private int attempt = 1;

    /** Why the last attempt failed, as disbursement says it; decides whether it is asked again or compensated. */
    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    /** To whom the money goes, as it was authorized: every attempt sends this same instruction. */
    @Column(name = "payee_name")
    private String payeeName;

    @Column(name = "payee_account", length = 20)
    private String payeeAccount;

    @Column(name = "payee_account_type", length = 4)
    private String payeeAccountType;

    @Column(name = "payee_tax_id", length = 18)
    private String payeeTaxId;

    @Column(name = "payee_concept", length = 40)
    private String payeeConcept;

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
        d.createdAt        = BusinessClock.current().now();
        return d;
    }

    /**
     * Una compra que nace <b>revolvente pura</b>: sin calendario, exigible entera en el corte.
     *
     * <p>Es la mecánica de una tarjeta. El titular puede diferirla después con {@link #diferir},
     * antes de que corte el ciclo.
     */
    public Disposition asPureRevolving() {
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
    public void defer(int plazo) {
        if (planMode == DispositionPlanMode.AMORTIZED) {
            throw new IllegalStateException(
                    "La disposición " + dispositionId + " ya tiene plan: no se difiere dos veces");
        }
        this.planMode     = DispositionPlanMode.AMORTIZED;
        this.deferredAt   = BusinessClock.current().now();
        this.deferredTerm = plazo;
    }

    /** Exigible entera en el próximo corte: nació revolvente, sigue sin diferirse y sin facturar. */
    public boolean isDueAtCutoff() {
        return planMode == DispositionPlanMode.REVOLVING && billedCycle == null;
    }

    /**
     * Entra al exigible de este ciclo.
     *
     * <p>Sin la marca, el corte siguiente volvería a exigir las mismas compras: {@code planMode}
     * sigue siendo {@code REVOLVING} después de facturarla.
     */
    public void billInCycle(int ciclo) {
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
        this.completedAt = BusinessClock.current().now();
    }

    public void fail() {
        fail(null, null);
    }

    /** Its payment could not happen, for good: the last attempt's failure says why. */
    public void fail(String failureCode, String failureReason) {
        this.status = DispositionStatus.FAILED;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
    }

    /** The payment it was authorized for, kept so that each attempt sends the same instruction. */
    public void recordPayee(String name, String account, String accountType, String taxId, String concept) {
        this.payeeName = name;
        this.payeeAccount = account;
        this.payeeAccountType = accountType;
        this.payeeTaxId = taxId;
        this.payeeConcept = concept;
    }

    /** Whether the instruction is known, and a new attempt can send it again (older dispositions did not keep it). */
    public boolean hasPayee() {
        return payeeAccount != null && !payeeAccount.isBlank() && payeeName != null && !payeeName.isBlank();
    }

    /**
     * The last attempt failed for a reason another may overcome: the next one is asked, and the disposition stays in
     * flight. The failure stays written until an attempt ends it.
     */
    public void retry(String failureCode, String failureReason) {
        if (status != DispositionStatus.PROCESSING) {
            throw new IllegalStateException("Only a disposition in flight is retried: " + status);
        }
        this.attempt++;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
    }

    /** An outcome of an attempt older than the one in flight: it says nothing about the payment any more. */
    public boolean isStale(int outcomeAttempt) {
        return outcomeAttempt < attempt;
    }

    /**
     * El banco receptor devolvió el dinero.
     *
     * <p>{@code REVERSED} y no {@code FAILED} porque son hechos distintos y se explican distinto al
     * cliente: en un fallo el pago nunca salió; en una devolución salió, llegó y volvió. La cuenta
     * queda igual en los dos casos, pero el expediente no.
     */
    public void reverse() {
        reverse(null);
    }

    /** The beneficiary's bank sent the money back: the reason it gave. */
    public void reverse(String reason) {
        this.status = DispositionStatus.REVERSED;
        this.failureCode = "RETURNED_BY_BENEFICIARY_BANK";
        this.failureReason = reason;
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
    public int getAttempt()             { return attempt; }
    public String getFailureCode()      { return failureCode; }
    public String getFailureReason()    { return failureReason; }
    public String getPayeeName()        { return payeeName; }
    public String getPayeeAccount()     { return payeeAccount; }
    public String getPayeeAccountType() { return payeeAccountType; }
    public String getPayeeTaxId()       { return payeeTaxId; }
    public String getPayeeConcept()     { return payeeConcept; }

    /**
     * Guarda qué decía el veredicto de listas en el instante de esta disposición.
     *
     * <p>No se actualiza nunca después. Si el re-cruce de las 10:30 marca a esta persona, esta foto
     * sigue diciendo lo que se sabía a las 10:06 — que es exactamente lo que hace defendible el
     * riesgo asumido.
     */
    public void recordAmlSnapshot(String screeningStatus, String listVersions, Instant verdictAt) {
        this.amlScreeningStatus = screeningStatus;
        this.amlListVersions    = listVersions;
        this.amlVerdictAt       = verdictAt;
        this.amlCheckedAt       = BusinessClock.current().now();
    }

    /**
     * Cuán viejo era el dato con el que se dejó salir el dinero.
     *
     * <p>Es la medida real de la ventana asumida, disposición por disposición: no «cuánto puede
     * durar el re-cruce» en teoría, sino cuánto tiempo de desfase hubo de verdad en cada caso.
     */
    public java.time.Duration amlDataAge() {
        return amlVerdictAt == null || amlCheckedAt == null
                ? null : java.time.Duration.between(amlVerdictAt, amlCheckedAt);
    }

    public String getAmlScreeningStatus() { return amlScreeningStatus; }
    public String getAmlListVersions()    { return amlListVersions; }
    public Instant getAmlVerdictAt()      { return amlVerdictAt; }
    public Instant getAmlCheckedAt()      { return amlCheckedAt; }
}
