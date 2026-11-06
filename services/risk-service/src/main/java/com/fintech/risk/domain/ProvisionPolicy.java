package com.fintech.risk.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

/**
 * Versioned rate matrix for a productType — one ACTIVE per productType (PP-01), same pattern as
 * ScoringPolicy (D2) and credit_product_definitions (D4). The 7 bucket bands are mandatory (PP-02):
 * a missing band is an explicit failure, never a silent default. The single {@code rateBands}
 * collection is EAGER on purpose — a single bag is safe, and it avoids the LazyInitialization trap
 * when the policy is mapped to a response outside a transaction.
 */
@Entity
@Table(name = "provision_policies", schema = "risk")
public class ProvisionPolicy {

    @Id
    @Column(name = "policy_id", nullable = false, updatable = false)
    private UUID policyId;

    @Column(name = "product_type", nullable = false, updatable = false)
    private String productType;

    @Column(nullable = false, updatable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PolicyStatus status;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<ProvisionRateBand> rateBands = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * The ECL floor of each stage: the 12-month ECL in Stage 1, the lifetime ECL in Stages 2 and 3. Null in the
     * policies written before stages counted (the band alone applies then).
     */
    @Column(name = "stage1_rate")
    private BigDecimal stage1Rate;

    @Column(name = "stage2_rate")
    private BigDecimal stage2Rate;

    @Column(name = "stage3_rate")
    private BigDecimal stage3Rate;

    /** Credit conversion factor: the share of the undrawn line expected to be drawn before a default. */
    @Column(name = "undrawn_ccf", nullable = false)
    private BigDecimal undrawnCcf = BigDecimal.ZERO;

    protected ProvisionPolicy() {}

    /** A policy with stage floors and a credit conversion factor besides the band matrix. */
    public static ProvisionPolicy create(String productType, int version, Map<DelinquencyBucket, BigDecimal> rates,
                                         Map<Ifrs9Stage, BigDecimal> stageFloors, BigDecimal undrawnCcf) {
        ProvisionPolicy p = create(productType, version, rates);
        p.stage1Rate = stageFloors.get(Ifrs9Stage.STAGE_1);
        p.stage2Rate = stageFloors.get(Ifrs9Stage.STAGE_2);
        p.stage3Rate = stageFloors.get(Ifrs9Stage.STAGE_3);
        p.undrawnCcf = undrawnCcf != null ? undrawnCcf : BigDecimal.ZERO;
        return p;
    }

    /**
     * Creates an ACTIVE policy. PP-02: every bucket must have a rate — missing any of the 7 is
     * rejected here, so a policy can never be persisted without a full matrix.
     */
    public static ProvisionPolicy create(String productType, int version, Map<DelinquencyBucket, BigDecimal> rates) {
        for (DelinquencyBucket b : DelinquencyBucket.values()) {
            if (rates.get(b) == null) {
                throw new InvalidPolicyStateException(
                        "ProvisionPolicy for " + productType + " is missing a rate for bucket " + b + " (PP-02)");
            }
        }
        ProvisionPolicy p = new ProvisionPolicy();
        p.policyId    = UUID.randomUUID();
        p.productType = productType;
        p.version     = version;
        p.status      = PolicyStatus.ACTIVE;
        p.createdAt   = BusinessClock.current().now();
        rates.forEach((bucket, rate) -> {
            ProvisionRateBand band = ProvisionRateBand.of(bucket, rate);
            band.assignTo(p);
            p.rateBands.add(band);
        });
        return p;
    }

    /**
     * The rate for an account in this stage and band: the higher of the stage's ECL floor and the band's rate. A
     * forborne account at 0 DPD is Stage 2 and reserves the lifetime floor, not the performing band; an account
     * still on probation out of Stage 3 keeps the Stage 3 floor.
     */
    public BigDecimal rateFor(Ifrs9Stage stage, DelinquencyBucket bucket) {
        BigDecimal banda = rateFor(bucket);
        BigDecimal piso = switch (stage) {
            case STAGE_1 -> stage1Rate;
            case STAGE_2 -> stage2Rate;
            case STAGE_3 -> stage3Rate;
        };
        return piso == null ? banda : piso.max(banda);
    }

    public BigDecimal getUndrawnCcf()      { return undrawnCcf != null ? undrawnCcf : BigDecimal.ZERO; }
    public BigDecimal getStage1Rate()      { return stage1Rate; }
    public BigDecimal getStage2Rate()      { return stage2Rate; }
    public BigDecimal getStage3Rate()      { return stage3Rate; }

    /** RC-02 input: the rate to apply for a bucket. PP-02: missing band fails, never defaults. */
    public BigDecimal rateFor(DelinquencyBucket bucket) {
        return rateBands.stream()
                .filter(b -> b.getBucket() == bucket)
                .map(ProvisionRateBand::getExpectedLossRate)
                .findFirst()
                .orElseThrow(() -> new InvalidPolicyStateException(
                        "ProvisionPolicy " + policyId + " has no band for bucket " + bucket + " (PP-02)"));
    }

    public void deprecate() {
        this.status = PolicyStatus.DEPRECATED;
    }

    public Map<DelinquencyBucket, BigDecimal> rateMatrix() {
        Map<DelinquencyBucket, BigDecimal> m = new EnumMap<>(DelinquencyBucket.class);
        rateBands.forEach(b -> m.put(b.getBucket(), b.getExpectedLossRate()));
        return m;
    }

    public boolean isActive()          { return status == PolicyStatus.ACTIVE; }

    public UUID getPolicyId()          { return policyId; }
    public String getProductType()     { return productType; }
    public int getVersion()            { return version; }
    public PolicyStatus getStatus()    { return status; }
    public List<ProvisionRateBand> getRateBands() { return List.copyOf(rateBands); }
    public Instant getCreatedAt()      { return createdAt; }
}
