package com.fintech.creditportfolio.domain.config;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

/**
 * Local read-model of a credit-product config version (ADR-001: read models locales).
 *
 * <p>Built by projecting {@code product-catalog.product-activated} events. Keyed logically by
 * {@code (productCode, productVersion)} — a CreditAccount pins the version it was originated
 * under and reads its behaviour config from here for its entire lifetime.
 *
 * <p>A version is immutable once written: credit-product never edits a version in place,
 * it publishes a new version and retires the old one. Retiring only flips {@code status}
 * (the version stays usable by accounts already pinned to it).
 */
@Entity
@Table(
    name = "product_config_versions",
    schema = "credit_portfolio",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_pcv_code_version",
        columnNames = {"product_code", "product_version"})
)
public class ProductConfigVersion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "product_code", nullable = false, updatable = false)
    private String productCode;

    @Column(name = "product_version", nullable = false, updatable = false)
    private int productVersion;

    @Column(name = "product_type", nullable = false)
    private String productType;

    /** INSTALLMENT | REVOLVING */
    @Column(name = "behavior", nullable = false)
    private String behavior;

    @Column(name = "target_audience")
    private String targetAudience;

    /** Capability matrix that drives the engine. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capabilities", nullable = false, columnDefinition = "jsonb")
    private Capabilities capabilities;

    /** The product's billing cycle, pinned to each account on activation; null for versions projected before it existed. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "billing_cycle", columnDefinition = "jsonb")
    private BillingCycle billingCycle;

    /** The distributor's commission terms (a distributor line); null for every other product and for older versions. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "distributor_commission", columnDefinition = "jsonb")
    private DistributorCommission distributorCommission;

    /** The product's amount and term ranges, what a placement is validated against; null when the catalog never sent them. */
    @Column(name = "min_amount")
    private BigDecimal minAmount;

    @Column(name = "max_amount")
    private BigDecimal maxAmount;

    @Column(name = "min_term")
    private Integer minTerm;

    @Column(name = "max_term")
    private Integer maxTerm;

    @Column(name = "term_step")
    private Integer termStep;

    @Column(name = "amortization_type")
    private String amortizationType;          // FRENCH | GERMAN | BULLET (null for revolving)

    @Column(name = "payment_frequency")
    private String paymentFrequency;          // WEEKLY | BIWEEKLY | SEMI_MONTHLY | MONTHLY

    @Column(name = "amount_step")
    private Integer amountStep;

    @Column(name = "nominal_rate_annual")
    private BigDecimal nominalRateAnnual;

    @Column(name = "moratorium_rate_annual")
    private BigDecimal moratoriumRateAnnual;

    @Column(name = "opening_fee_rate")
    private BigDecimal openingFeeRate;

    /** ACTIVE | RETIRED — retired versions remain usable by pinned accounts. */
    @Column(name = "status", nullable = false)
    private String status;

    /** True when materialized from a degraded fallback (not yet from the authoritative event). */
    @Column(name = "degraded", nullable = false)
    private boolean degraded;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProductConfigVersion() {}

    public static ProductConfigVersion of(
            String productCode, int productVersion, String productType, String behavior,
            String targetAudience, Capabilities capabilities,
            String amortizationType, String paymentFrequency, Integer amountStep,
            BigDecimal nominalRateAnnual, BigDecimal moratoriumRateAnnual, BigDecimal openingFeeRate,
            String status, boolean degraded) {

        Instant now = BusinessClock.current().now();
        ProductConfigVersion v = new ProductConfigVersion();
        v.id                   = UUID.randomUUID();
        v.productCode          = productCode;
        v.productVersion       = productVersion;
        v.productType          = productType;
        v.behavior             = behavior;
        v.targetAudience       = targetAudience;
        v.capabilities         = capabilities;
        v.amortizationType     = amortizationType;
        v.paymentFrequency     = paymentFrequency;
        v.amountStep           = amountStep;
        v.nominalRateAnnual    = nominalRateAnnual;
        v.moratoriumRateAnnual = moratoriumRateAnnual;
        v.openingFeeRate       = openingFeeRate;
        v.status               = status;
        v.degraded             = degraded;
        v.receivedAt           = now;
        v.updatedAt            = now;
        return v;
    }

    /** Overwrites config fields from the authoritative event (clears degraded flag). */
    public void overwriteFrom(String productType, String behavior, String targetAudience,
                              Capabilities capabilities, String amortizationType,
                              String paymentFrequency, Integer amountStep,
                              BigDecimal nominalRateAnnual, BigDecimal moratoriumRateAnnual,
                              BigDecimal openingFeeRate, String status) {
        this.productType          = productType;
        this.behavior             = behavior;
        this.targetAudience       = targetAudience;
        this.capabilities         = capabilities;
        this.amortizationType     = amortizationType;
        this.paymentFrequency     = paymentFrequency;
        this.amountStep           = amountStep;
        this.nominalRateAnnual    = nominalRateAnnual;
        this.moratoriumRateAnnual = moratoriumRateAnnual;
        this.openingFeeRate       = openingFeeRate;
        this.status               = status;
        this.degraded             = false;
        this.updatedAt            = BusinessClock.current().now();
    }

    public void markRetired() {
        this.status    = "RETIRED";
        this.updatedAt = BusinessClock.current().now();
    }

    public BillingCycle getBillingCycle()     { return billingCycle; }
    public void defineBillingCycle(BillingCycle cycle) {
        if (cycle != null) this.billingCycle = cycle;
    }
    public UUID getId()                       { return id; }
    public String getProductCode()            { return productCode; }
    public int getProductVersion()            { return productVersion; }
    public String getProductType()            { return productType; }
    public String getBehavior()               { return behavior; }
    public String getTargetAudience()         { return targetAudience; }
    public Capabilities getCapabilities()     { return capabilities; }
    public String getAmortizationType()       { return amortizationType; }
    public String getPaymentFrequency()       { return paymentFrequency; }
    public Integer getAmountStep()            { return amountStep; }
    public BigDecimal getNominalRateAnnual()  { return nominalRateAnnual; }
    public BigDecimal getMoratoriumRateAnnual(){ return moratoriumRateAnnual; }
    public BigDecimal getOpeningFeeRate()     { return openingFeeRate; }
    public DistributorCommission getDistributorCommission() { return distributorCommission; }
    public BigDecimal getMinAmount()          { return minAmount; }
    public BigDecimal getMaxAmount()          { return maxAmount; }
    public Integer getMinTerm()               { return minTerm; }
    public Integer getMaxTerm()               { return maxTerm; }
    public Integer getTermStep()              { return termStep; }

    /** The distributor's commission terms; an event without them (an older catalog) leaves what the version has. */
    public void defineDistributorCommission(DistributorCommission commission) {
        if (commission != null) this.distributorCommission = commission;
    }

    /** The product's ranges; an event without them leaves what the version has. */
    public void defineRanges(BigDecimal minAmount, BigDecimal maxAmount, Integer minTerm, Integer maxTerm, Integer termStep) {
        if (minAmount != null) this.minAmount = minAmount;
        if (maxAmount != null) this.maxAmount = maxAmount;
        if (minTerm != null)   this.minTerm = minTerm;
        if (maxTerm != null)   this.maxTerm = maxTerm;
        if (termStep != null)  this.termStep = termStep;
    }

    /**
     * Why a placement of {@code amount} over {@code term} periods does not fit the product; null when it does.
     *
     * <p>The portfolio holds the product's limits: the amounts between its minimum and maximum, the terms between
     * its minimum and maximum in the product's step (the pricing is defined for those terms). The amount step is the
     * channel's picker, not a limit: a placement of $4,000.02 keyed by an operator is as valid as one of $4,000, and
     * refusing it here only rejected the orders whose cents the E2E uses to drive the SPEI connector's failure paths.
     */
    public String placementOutOfRange(BigDecimal amount, Integer term) {
        if (amount != null) {
            if (minAmount != null && amount.compareTo(minAmount) < 0) {
                return "AMOUNT_BELOW_MINIMUM: amount=" + amount + " min=" + minAmount;
            }
            if (maxAmount != null && amount.compareTo(maxAmount) > 0) {
                return "AMOUNT_ABOVE_MAXIMUM: amount=" + amount + " max=" + maxAmount;
            }
        }
        if (term != null) {
            if (minTerm != null && term < minTerm) return "TERM_BELOW_MINIMUM: term=" + term + " min=" + minTerm;
            if (maxTerm != null && term > maxTerm) return "TERM_ABOVE_MAXIMUM: term=" + term + " max=" + maxTerm;
            if (termStep != null && termStep > 1 && minTerm != null && (term - minTerm) % termStep != 0) {
                return "TERM_NOT_IN_STEP: term=" + term + " step=" + termStep + " from=" + minTerm;
            }
        }
        return null;
    }

    public String getStatus()                 { return status; }
    public boolean isDegraded()               { return degraded; }
    public Instant getReceivedAt()            { return receivedAt; }
    public Instant getUpdatedAt()             { return updatedAt; }
}
