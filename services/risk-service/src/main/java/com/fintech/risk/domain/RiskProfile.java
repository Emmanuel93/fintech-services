package com.fintech.risk.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

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

    /** When the account first qualified for a better stage; it steps down once its probation elapses. */
    @Column(name = "probation_since")
    private Instant probationSince;

    /** The undrawn part of a revolving line (available credit): exposure at the policy's conversion factor. */
    @Column(name = "undrawn", nullable = false)
    private BigDecimal undrawn = BigDecimal.ZERO;

    protected RiskProfile() {}

    /** CreditAccountActivated → new profile in STAGE_1 / CURRENT / ead=0 / provision=0. */
    public static RiskProfile create(UUID creditAccountId, UUID obligorPartyId, String productType) {
        RiskProfile p = new RiskProfile();
        Instant now = BusinessClock.current().now();
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

    /** The drawn exposure and, for a revolving line, the credit still available to draw. */
    public void syncExposure(BigDecimal totalDebt, BigDecimal availableCredit) {
        if (status.isClosed()) return;
        if (totalDebt != null) this.ead = totalDebt;
        if (availableCredit != null) this.undrawn = availableCredit.max(BigDecimal.ZERO);
    }

    /**
     * The loan was paid off: nothing is expected to be lost any more. The reserve goes to zero (and accounting
     * releases it) before the profile closes; a closed profile with a reserve left 5 settled loans holding $220.40
     * in 1290 in the 2 Nov E2E.
     */
    public void settle(Instant now) {
        this.provisionAmount  = BigDecimal.ZERO;
        this.expectedLossRate = BigDecimal.ZERO;
        this.lastCalculatedAt = now;
        this.status           = RiskProfileStatus.CLOSED;
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
     * Written off: the loan left the balance sheet and its reserve was consumed by the write-off itself, in
     * accounting. The profile closes with nothing reserved and nothing exposed. It is not published: a zero arriving
     * before the write-off would release the reserve the write-off has to consume.
     */
    public void writeOff(Instant now) {
        this.provisionAmount  = BigDecimal.ZERO;
        this.expectedLossRate = BigDecimal.ZERO;
        this.ead              = BigDecimal.ZERO;
        this.lastCalculatedAt = now;
        this.status           = RiskProfileStatus.CLOSED;
    }

    /**
     * Nightly reassessment, IFRS 9 by stage. The stage comes from days past due (30-day SICR backstop, 90-day
     * default), floored at Stage 2 while a forbearance cure runs. Moving to a worse stage is immediate; moving to a
     * better one takes a probation: the account must keep qualifying for {@code probationDays(current)} days, and
     * steps down one stage at a time. The rate is the policy's for the stage and band (stage floor or band, the
     * higher), applied to the drawn exposure plus the undrawn credit at the policy's conversion factor.
     *
     * <p>Before, the rate came from the band alone, so Stage 2 reserved like Stage 1, and a cure released the
     * reserve the next day (2 Nov E2E: +$3,017.09 at 31 DPD, −$3,613.25 one day after catching up).
     */
    public boolean reassess(int stage2Threshold, int stage3Threshold, int cureMonths, int stage3ProbationDays,
                            int stage2ProbationDays, ProvisionPolicy policy, Instant now) {
        if (status.isClosed()) return false;

        this.bucket = DelinquencyBucket.fromDaysDelinquent(daysDelinquent);
        Ifrs9Stage base = Ifrs9StageResolver.baseStage(daysDelinquent, stage2Threshold, stage3Threshold);
        boolean inCureWindow = isForborne && stageEnteredAt != null && now.isBefore(cureCutoff(cureMonths));
        if (isForborne && !inCureWindow) {
            this.isForborne = false;
        }
        Ifrs9Stage target = inCureWindow ? Ifrs9Stage.atLeast(base, Ifrs9Stage.STAGE_2) : base;

        Ifrs9Stage next = ifrs9Stage;
        if (target.ordinal() > ifrs9Stage.ordinal()) {
            next = target;
            this.probationSince = null;
        } else if (target.ordinal() < ifrs9Stage.ordinal()) {
            if (probationSince == null) {
                this.probationSince = now;
            } else {
                int dias = ifrs9Stage == Ifrs9Stage.STAGE_3 ? stage3ProbationDays : stage2ProbationDays;
                if (!now.isBefore(probationSince.plus(Duration.ofDays(dias)))) {
                    next = Ifrs9Stage.values()[ifrs9Stage.ordinal() - 1];
                    // Another step to go: its probation starts now.
                    this.probationSince = next.ordinal() > target.ordinal() ? now : null;
                }
            }
        } else {
            this.probationSince = null;
        }

        boolean stageChanged = next != this.ifrs9Stage;
        if (stageChanged) {
            this.ifrs9Stage = next;
            this.stageEnteredAt = now;
        }
        BigDecimal rate = policy.rateFor(this.ifrs9Stage, this.bucket);
        BigDecimal exposicion = ead.add(undrawn.multiply(policy.getUndrawnCcf()));
        this.expectedLossRate = rate;
        this.provisionAmount = exposicion.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        this.lastCalculatedAt = now;
        return stageChanged;
    }

    public Instant getProbationSince()      { return probationSince; }
    public BigDecimal getUndrawn()          { return undrawn; }

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
