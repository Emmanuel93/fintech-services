package com.fintech.scoring.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

@Entity
@Table(name = "bureau_prefetches", schema = "scoring")
public class BureauPrefetch {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID prefetchId;

    @Column(nullable = false, updatable = false)
    private UUID prospectId;

    @Column(nullable = false, updatable = false, length = 18)
    private String curp;

    /** ID del evento que originó el consentimiento — trazabilidad regulatoria */
    @Column(nullable = false, updatable = false, length = 100)
    private String consentRef;

    /** INDIVIDUAL | BUSINESS — determina el modelo de scoring a aplicar */
    @Column(updatable = false, length = 20)
    private String prospectType;

    /** Producto de interés (PERSONAL_LOAN, REVOLVING_LINE, etc.) — selección de modelo */
    @Column(updatable = false, length = 30)
    private String productTypeIntent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BureauPrefetchStatus status;

    @Column(nullable = false, updatable = false)
    private Instant requestedAt;

    private Instant completedAt;

    @Column(length = 500)
    private String failureReason;

    /** Cuántas veces se llamó al buró en este prefetch, contando los reintentos. */
    @Column(nullable = false)
    private int attempts;

    protected BureauPrefetch() {}

    public static BureauPrefetch create(UUID prefetchId, UUID prospectId, String curp,
                                        String consentRef,
                                        String prospectType, String productTypeIntent) {
        BureauPrefetch p = new BureauPrefetch();
        p.prefetchId        = prefetchId;
        p.prospectId        = prospectId;
        p.curp              = curp;
        p.consentRef        = consentRef;
        p.prospectType      = prospectType;
        p.productTypeIntent = productTypeIntent;
        p.status            = BureauPrefetchStatus.PENDING;
        p.requestedAt       = BusinessClock.current().now();
        return p;
    }

    public void markInProgress() {
        this.status = BureauPrefetchStatus.IN_PROGRESS;
    }

    public void markCompleted() {
        this.status      = BureauPrefetchStatus.COMPLETED;
        this.completedAt = BusinessClock.current().now();
    }

    public void markFailed(String reason) {
        this.status        = BureauPrefetchStatus.FAILED;
        // La columna es de 500: un mensaje de excepción más largo no debe tumbar el registro del fallo.
        this.failureReason = reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason;
        this.completedAt   = BusinessClock.current().now();
    }

    /** Una llamada más al buró: el primer intento o un reintento. */
    public void recordAttempt() {
        this.attempts++;
    }

    public UUID getPrefetchId()             { return prefetchId; }
    public UUID getProspectId()             { return prospectId; }
    public String getCurp()                 { return curp; }
    public String getConsentRef()           { return consentRef; }
    public String getProspectType()         { return prospectType; }
    public String getProductTypeIntent()    { return productTypeIntent; }
    public BureauPrefetchStatus getStatus() { return status; }
    public Instant getRequestedAt()         { return requestedAt; }
    public Instant getCompletedAt()         { return completedAt; }
    public String getFailureReason()        { return failureReason; }
    public int getAttempts()                { return attempts; }
}
