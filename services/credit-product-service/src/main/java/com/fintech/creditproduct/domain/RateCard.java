package com.fintech.creditproduct.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Tiered pricing entry for a product definition.
 *
 * <p>A rate card row matches a request when ALL non-null bands contain the requested value.
 * Resolution: most-specific match first (fewest nulls wins).  If no row matches, the
 * product definition's flat nominalRateAnnual / moratoriumRateAnnual are used as fallback.
 *
 * <p>Use cases:
 * <ul>
 *   <li>B2C PERSONAL_LOAN: rate by risk tier (T1=28%, T2=32%, T3=38%)</li>
 *   <li>B2B2C DISTRIBUTOR_LINE: rate by line amount band ($100k–$500k = 18%, $500k+ = 16%)</li>
 *   <li>B2B SME_LOAN: rate by term (12m=24%, 24m=26%, 36m=28%)</li>
 * </ul>
 */
@Entity
@Table(
    name = "rate_cards",
    schema = "credit_product",
    indexes = {
        @Index(name = "idx_rc_product_def", columnList = "product_definition_id")
    }
)
public class RateCard {

    @Id
    @Column(name = "rate_card_id", nullable = false, updatable = false)
    private UUID rateCardId;

    @Column(name = "product_definition_id", nullable = false, updatable = false)
    private UUID productDefinitionId;

    /** Risk tier — null means this row applies to any tier. */
    @Column(name = "tier_band", length = 10)
    private String tierBand;

    /** Amount lower bound (inclusive) — null means no lower bound. */
    @Column(name = "min_amount", precision = 19, scale = 4)
    private BigDecimal minAmount;

    /** Amount upper bound (inclusive) — null means no upper bound. */
    @Column(name = "max_amount", precision = 19, scale = 4)
    private BigDecimal maxAmount;

    /** Term lower bound in months (inclusive) — null means no lower bound. */
    @Column(name = "min_term")
    private Integer minTerm;

    /** Term upper bound in months (inclusive) — null means no upper bound. */
    @Column(name = "max_term")
    private Integer maxTerm;

    @Column(name = "nominal_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal nominalRate;

    @Column(name = "moratorium_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal moratoriumRate;

    protected RateCard() {}

    public static RateCard create(
            UUID productDefinitionId,
            String tierBand,
            BigDecimal minAmount, BigDecimal maxAmount,
            Integer minTerm, Integer maxTerm,
            BigDecimal nominalRate, BigDecimal moratoriumRate) {

        RateCard rc = new RateCard();
        rc.rateCardId          = UUID.randomUUID();
        rc.productDefinitionId = productDefinitionId;
        rc.tierBand            = tierBand;
        rc.minAmount           = minAmount;
        rc.maxAmount           = maxAmount;
        rc.minTerm             = minTerm;
        rc.maxTerm             = maxTerm;
        rc.nominalRate         = nominalRate;
        rc.moratoriumRate      = moratoriumRate;
        return rc;
    }

    /** Returns true when this row applies to the given (tier, amount, term) combination. */
    public boolean matches(String tier, BigDecimal amount, Integer term) {
        if (tierBand != null && !tierBand.equalsIgnoreCase(tier)) return false;
        if (minAmount != null && amount != null && amount.compareTo(minAmount) < 0) return false;
        if (maxAmount != null && amount != null && amount.compareTo(maxAmount) > 0) return false;
        if (minTerm != null && term != null && term < minTerm) return false;
        if (maxTerm != null && term != null && term > maxTerm) return false;
        return true;
    }

    /** Specificity score — higher means more constraints (prefer over wildcard rows). */
    public int specificity() {
        int s = 0;
        if (tierBand  != null) s++;
        if (minAmount != null || maxAmount != null) s++;
        if (minTerm   != null || maxTerm   != null) s++;
        return s;
    }

    public UUID getRateCardId()          { return rateCardId; }
    public UUID getProductDefinitionId() { return productDefinitionId; }
    public String getTierBand()          { return tierBand; }
    public BigDecimal getMinAmount()     { return minAmount; }
    public BigDecimal getMaxAmount()     { return maxAmount; }
    public Integer getMinTerm()          { return minTerm; }
    public Integer getMaxTerm()          { return maxTerm; }
    public BigDecimal getNominalRate()   { return nominalRate; }
    public BigDecimal getMoratoriumRate(){ return moratoriumRate; }
}
