package com.fintech.risk.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Aggregate root — one per creditAccountId. Mirrors credit-portfolio's delinquency/balance signals
 * (synced reactively) but only transitions {@code ifrs9Stage} in the nightly reassessment (RC-03).
 * Never modifies balances — it classifies risk and computes the provision to reserve.
 */
@Entity
@Table(name = "risk_profiles", schema = "risk")
public class RiskProfile {

    @Id
    @Column(name = "risk_profile_id", nullable = false, updatable = false)
    private UUID riskProfileId;

    @Column(name = "credit_account_id", nullable = false, updatable = false, unique = true)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false)
    private UUID obligorPartyId;

    @Column(name = "product_type", nullable = false)
    private String productType;

    @Column(name = "days_delinquent", nullable = false)
    private int daysDelinquent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DelinquencyBucket bucket;

    @Enumerated(EnumType.STRING)
    @Column(name = "ifrs9_stage", nullable = false)
    private Ifrs9Stage ifrs9Stage;

    @Column(name = "stage_entered_at", nullable = false)
    private Instant stageEnteredAt;

    @Column(name = "is_forborne", nullable = false)
    private boolean isForborne;

    @Column(nullable = false)
    private BigDecimal ead;

    @Column(name = "expected_loss_rate", nullable = false)
    private BigDecimal expectedLossRate;

    @Column(name = "provision_amount", nullable = false)
    private BigDecimal provisionAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskProfileStatus status;

    @Column(name = "last_calculated_at")
    private Instant lastCalculatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RiskProfile() {}

    /** CreditAccountActivated → new profile in STAGE_1 / CURRENT / ead=0 / provision=0. */
    public static RiskProfile create(UUID creditAccountId, UUID obligorPartyId, String productType) {
        RiskProfile p = new RiskProfile();
        Instant now = Instant.now();
        p.riskProfileId    = UUID.randomUUID();
        p.creditAccountId  = creditAccountId;
        p.obligorPartyId   = obligorPartyId;
        p.productType      = productType != null ? productType : "UNKNOWN";
        p.daysDelinquent   = 0;
        p.bucket           = DelinquencyBucket.CURRENT;
        p.ifrs9Stage       = Ifrs9Stage.STAGE_1;
        p.stageEnteredAt   = now;
        p.isForborne       = false;
        p.ead              = BigDecimal.ZERO;
        p.expectedLossRate = BigDecimal.ZERO;
        p.provisionAmount  = BigDecimal.ZERO;
        p.status           = RiskProfileStatus.ACTIVE;
        p.createdAt        = now;
        return p;
    }

    /** PR-02: ead is a snapshot of the last BalanceUpdated.totalDebt — no synchronous read. */
    public void syncEad(BigDecimal totalDebt) {
        if (status.isClosed() || totalDebt == null) return;
        this.ead = totalDebt;
    }

    /** Sync days + local bucket (RC-01). Does NOT transition the stage (RC-03 — that's the job's job). */
    public void syncDaysDelinquent(int days) {
        if (status.isClosed()) return;
        this.daysDelinquent = days;
        this.bucket = DelinquencyBucket.fromDaysDelinquent(days);
    }

    /**
     * RC-04: an executed RESTRUCTURE marks the account as forborne and restarts the cure clock. The
     * STAGE_2 floor itself is applied by the next reassessment (RC-03 — no reactive stage change).
     */
    public void markForborne(Instant now) {
        if (status.isClosed()) return;
        this.isForborne = true;
        this.stageEnteredAt = now;
    }

    /** ES-03/RC-06: ProductSettled/ProductWrittenOff → CLOSED, provisionAmount frozen. Idempotent. */
    public void close() {
        this.status = RiskProfileStatus.CLOSED;
    }

    /**
     * Nightly reassessment (RC-03): recompute bucket + stage + provision from the current
     * daysDelinquent/ead and the supplied rate. Applies the forbearance floor while inside the cure
     * window (RC-04) and the sticky-down-from-STAGE_3 rule (RC-05). Once the cure window elapses the
     * forbearance flag clears so the account can step back down.
     *
     * @return true if the stage changed (Accounting still books every period regardless).
     */
    public boolean reassess(int stage2Threshold, int stage3Threshold, int cureMonths,
                             BigDecimal newExpectedLossRate, Instant now) {
        if (status.isClosed()) return false;

        this.bucket = DelinquencyBucket.fromDaysDelinquent(daysDelinquent);
        Ifrs9Stage base = Ifrs9StageResolver.baseStage(daysDelinquent, stage2Threshold, stage3Threshold);

        boolean inCureWindow = isForborne && stageEnteredAt != null && now.isBefore(cureCutoff(cureMonths));
        Ifrs9Stage target = Ifrs9StageResolver.targetStage(base, ifrs9Stage, inCureWindow);

        // Cure window elapsed → drop the forbearance flag so future reassessments can step down.
        if (isForborne && !inCureWindow) {
            this.isForborne = false;
        }

        boolean stageChanged = target != this.ifrs9Stage;
        if (stageChanged) {
            this.ifrs9Stage = target;
            this.stageEnteredAt = now;
        }

        this.expectedLossRate = newExpectedLossRate;
        this.provisionAmount = ead.multiply(newExpectedLossRate);  // RC-02
        this.lastCalculatedAt = now;
        return stageChanged;
    }

    private Instant cureCutoff(int cureMonths) {
        return stageEnteredAt.atZone(ZoneOffset.UTC).plusMonths(cureMonths).toInstant();
    }

    public UUID getRiskProfileId()          { return riskProfileId; }
    public UUID getCreditAccountId()        { return creditAccountId; }
    public UUID getObligorPartyId()         { return obligorPartyId; }
    public String getProductType()          { return productType; }
    public int getDaysDelinquent()          { return daysDelinquent; }
    public DelinquencyBucket getBucket()    { return bucket; }
    public Ifrs9Stage getIfrs9Stage()       { return ifrs9Stage; }
    public Instant getStageEnteredAt()      { return stageEnteredAt; }
    public boolean isForborne()             { return isForborne; }
    public BigDecimal getEad()              { return ead; }
    public BigDecimal getExpectedLossRate() { return expectedLossRate; }
    public BigDecimal getProvisionAmount()  { return provisionAmount; }
    public RiskProfileStatus getStatus()    { return status; }
    public Instant getLastCalculatedAt()    { return lastCalculatedAt; }
    public Instant getCreatedAt()           { return createdAt; }
}
