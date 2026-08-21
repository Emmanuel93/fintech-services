package com.fintech.risk.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One expected-loss rate per delinquency bucket, inside a {@link ProvisionPolicy}. The rate is
 * captured by the risk team, never produced by a model within this domain (RC-07).
 */
@Entity
@Table(name = "provision_rate_bands", schema = "risk")
public class ProvisionRateBand {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id", nullable = false)
    private ProvisionPolicy policy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private DelinquencyBucket bucket;

    @Column(name = "expected_loss_rate", nullable = false, updatable = false)
    private BigDecimal expectedLossRate;

    protected ProvisionRateBand() {}

    static ProvisionRateBand of(DelinquencyBucket bucket, BigDecimal expectedLossRate) {
        ProvisionRateBand b = new ProvisionRateBand();
        b.id               = UUID.randomUUID();
        b.bucket           = bucket;
        b.expectedLossRate = expectedLossRate;
        return b;
    }

    void assignTo(ProvisionPolicy policy) { this.policy = policy; }

    public UUID getId()                    { return id; }
    public DelinquencyBucket getBucket()   { return bucket; }
    public BigDecimal getExpectedLossRate(){ return expectedLossRate; }
}
