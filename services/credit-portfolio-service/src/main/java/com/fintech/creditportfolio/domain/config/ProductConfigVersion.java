package com.fintech.creditportfolio.domain.config;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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

    @Column(name = "amortization_type")
    private String amortizationType;          // FRENCH | GERMAN | BULLET (null for revolving)

    @Column(name = "payment_frequency")
    private String paymentFrequency;          // WEEKLY | BIWEEKLY | MONTHLY

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

        Instant now = Instant.now();
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
        this.updatedAt            = Instant.now();
    }

    public void markRetired() {
        this.status    = "RETIRED";
        this.updatedAt = Instant.now();
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
    public String getStatus()                 { return status; }
    public boolean isDegraded()               { return degraded; }
    public Instant getReceivedAt()            { return receivedAt; }
    public Instant getUpdatedAt()             { return updatedAt; }
}
