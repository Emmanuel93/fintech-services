package com.fintech.closing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * La unidad de trabajo: una cuenta/producto dentro de una corrida.
 *
 * <p>Es la fila que los pods se reparten. Tres cosas que el as-is no tiene y aquí sí:
 * <b>{@code CLAIMED} con dueño</b> —quién la está trabajando—, <b>{@code FAILED} con causa</b>
 * —qué pasó exactamente— y el <b>retorno desde arrendamiento vencido</b>: hoy el pod que muere se
 * lleva su lote en silencio y nadie se entera hasta que cuadra mal el mes.
 */
@Entity
@Table(name = "close_units", schema = "closing")
public class CloseUnit {

    @Id
    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "unit_key", nullable = false, length = 80)
    private String unitKey;

    @Column(name = "credit_account_id")
    private UUID creditAccountId;

    @Column(name = "product_type", length = 40)
    private String productType;

    /** De quién es la cuenta. Viaja con la unidad para que el reparto no vuelva a leer el perfil. */
    @Column(name = "obligor_party_id")
    private UUID obligorPartyId;

    @Column(name = "product_behavior", length = 20)
    private String productBehavior;

    /** {@code CUSTOMER} o {@code DISTRIBUTOR}: el corte se reparte por cliente y por distribuidora. */
    @Column(name = "holder_kind", length = 20)
    private String holderKind;

    /** Cuándo salió al tópico de trabajo. Nula = todavía no; vieja y aún PENDING = hay que reenviarla. */
    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "lease_owner", length = 60)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    /**
     * Rechaza al escritor cuyo candado ya expiró. Un candado con TTL puede vencer con el trabajo
     * todavía vivo —una pausa de GC basta— y entonces dos procesos se creen dueños. El número
     * monótono distingue al titular actual del anterior.
     */
    @Column(name = "fencing_token")
    private Long fencingToken;

    @Column(name = "attempts", nullable = false)
    private short attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "balance_version")
    private Long balanceVersion;

    /** From the portfolio's snapshot of D: what the statement prints as days past due. */
    @Column(name = "days_delinquent_at_day")
    private Integer daysDelinquentAtDay;

    /** From the same snapshot: the principal closing's own rewind to D must agree with. */
    @Column(name = "principal_at_day")
    private BigDecimal principalAtDay;

    @Column(name = "snapshot_taken_at")
    private Instant snapshotTakenAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CloseUnit() {}

    public static CloseUnit of(UUID runId, String unitKey, UUID creditAccountId, String productType) {
        CloseUnit u = new CloseUnit();
        u.unitId          = UUID.randomUUID();
        u.runId           = runId;
        u.unitKey         = unitKey;
        u.creditAccountId = creditAccountId;
        u.productType     = productType;
        u.status          = UnitStatus.PENDING.name();
        u.createdAt       = Instant.now();
        return u;
    }

    public void claim(String owner, long fencingToken, Instant expiresAt) {
        this.status         = UnitStatus.CLAIMED.name();
        this.leaseOwner     = owner;
        this.fencingToken   = fencingToken;
        this.leaseExpiresAt = expiresAt;
        this.attempts       = (short) (attempts + 1);
    }

    /**
     * @throws IllegalStateException si el token es menor al registrado — el titular ya no lo es y
     *         su escritura no es de fiar. Es la razón de ser del fencing token.
     */
    public void markDone(long fencingToken) {
        guardFencing(fencingToken);
        this.status      = UnitStatus.DONE.name();
        this.completedAt = Instant.now();
        this.leaseOwner  = null;
        this.leaseExpiresAt = null;
    }

    public void markFailed(long fencingToken, String error) {
        guardFencing(fencingToken);
        this.status    = UnitStatus.FAILED.name();
        this.lastError = error != null && error.length() > 500 ? error.substring(0, 500) : error;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
    }

    public void markSkipped(String reason) {
        this.status    = UnitStatus.SKIPPED.name();
        this.lastError = reason;
        this.completedAt = Instant.now();
    }

    /** El arrendamiento venció: vuelve a la cola. El intento ya quedó contado. */
    public void release() {
        this.status         = UnitStatus.PENDING.name();
        this.leaseOwner     = null;
        this.leaseExpiresAt = null;
        this.fencingToken   = null;
    }

    private void guardFencing(long token) {
        if (fencingToken != null && token < fencingToken) {
            throw new IllegalStateException(
                    "Escritura rechazada en la unidad " + unitKey + ": el token " + token
                    + " es anterior al titular actual (" + fencingToken + ")");
        }
    }

    public UUID getUnitId()          { return unitId; }
    public UUID getRunId()           { return runId; }
    public String getUnitKey()       { return unitKey; }
    public UUID getCreditAccountId() { return creditAccountId; }
    public String getProductType()   { return productType; }
    public UUID getObligorPartyId()  { return obligorPartyId; }
    public String getProductBehavior() { return productBehavior; }
    public String getHolderKind()    { return holderKind; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public UnitStatus status()       { return UnitStatus.valueOf(status); }
    public String getLeaseOwner()    { return leaseOwner; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public Long getFencingToken()    { return fencingToken; }
    public short getAttempts()       { return attempts; }
    public String getLastError()     { return lastError; }
    public void setBalanceVersion(Long v) { this.balanceVersion = v; }
    public Long getBalanceVersion()  { return balanceVersion; }

    /** Attaches what the portfolio saw at the end of D; normally done in bulk when the run is planned. */
    public void attachSnapshot(int daysDelinquent, BigDecimal principal, Instant takenAt) {
        this.daysDelinquentAtDay = daysDelinquent;
        this.principalAtDay      = principal;
        this.snapshotTakenAt     = takenAt;
    }
    public boolean hasSnapshot()            { return daysDelinquentAtDay != null; }
    public Integer getDaysDelinquentAtDay() { return daysDelinquentAtDay; }
    public BigDecimal getPrincipalAtDay()   { return principalAtDay; }
    public Instant getSnapshotTakenAt()     { return snapshotTakenAt; }
}
