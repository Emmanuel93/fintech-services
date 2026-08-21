package com.fintech.creditproduct.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregate root — versioned product definition (catalog layer).
 *
 * <p>Invariants:
 * <ul>
 *   <li>PD-01: at most one ACTIVE row per productCode — enforced by partial unique index</li>
 *   <li>PD-02: activating a new version auto-retires the previous ACTIVE version</li>
 *   <li>PD-03: RETIRED/DEPRECATED rows are immutable</li>
 *   <li>PD-04: dispositionType + capabilities must be coherent with productType</li>
 *   <li>PD-05: catalog never deletes — versions only</li>
 * </ul>
 */
@Entity
@Table(name = "credit_product_definitions", schema = "credit_product")
public class CreditProductDefinition {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID productDefinitionId;

    /** Stable business key — same across all versions of a product. */
    @Column(nullable = false, updatable = false, length = 50)
    private String productCode;

    /** Incremental business version (1, 2, 3…). Separate from @Version optimistic lock. */
    @Column(nullable = false)
    private int productVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private ProductType productType;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TargetAudience targetAudience;

    @Column(nullable = false, length = 3)
    private String currency;

    // ── Flat rates (fallback when no rate_card row matches) ───────────────────
    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal nominalRateAnnual;

    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal moratoriumRateAnnual;

    // ── Plazo en meses (null para REVOLVING) ──────────────────────────────────
    private Integer minTerm;
    private Integer maxTerm;
    private Integer defaultTerm;

    // ── Montos (null para REVOLVING) ──────────────────────────────────────────
    @Column(precision = 19, scale = 4)
    private BigDecimal minAmount;

    @Column(precision = 19, scale = 4)
    private BigDecimal maxAmount;

    // ── Línea de crédito (null para INSTALLMENT) ──────────────────────────────
    @Column(precision = 19, scale = 4)
    private BigDecimal defaultCreditLine;

    @Column(precision = 19, scale = 4)
    private BigDecimal minCreditLine;

    @Column(precision = 19, scale = 4)
    private BigDecimal maxCreditLine;

    /**
     * Minimum amount increment (step) — null means no constraint.
     * INSTALLMENT: loan amounts must be multiples of this value (e.g. 1000 → $5k, $6k, $7k).
     * REVOLVING:   credit lines must be multiples of this value (e.g. 1000 → $10k, $11k, $12k).
     * Typical values: 500 (micro/group loans), 1000 (personal/SME), 5000 (distributor lines).
     */
    @Column(name = "amount_step")
    private Integer amountStep;

    /**
     * Minimum term increment (step) — the twin of {@link #amountStep}, defaults to 1.
     *
     * <p>Without it, a stepper that only offers 12/24/36/48 (step 12) or every other fortnight
     * (step 2) can't be expressed from {@code minTerm}/{@code maxTerm} alone: the range would
     * imply every value in between is on offer, and the caller would have no way to know it isn't.
     */
    @Column(name = "term_step", nullable = false)
    private Integer termStep = 1;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AmortizationType amortizationType;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentFrequency defaultPaymentFrequency;

    @Column(nullable = false)
    private Integer minApprovalScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApprovalFlow defaultApprovalFlow;

    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal openingFeeRate;

    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal prepaymentFeeRate;

    /**
     * Capability matrix — governs credit-portfolio motor behaviour.
     * Defaults derived from productType; configurable per version for flexibility.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Capabilities capabilities;

    // ── Timestamps ────────────────────────────────────────────────────────────
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant activatedAt;
    private Instant retiredAt;
    private Instant deprecatedAt;

    @Version
    private Long version;

    // ── Collections ───────────────────────────────────────────────────────────

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "credit_product_eligible_party_types",
            schema = "credit_product",
            joinColumns = @JoinColumn(name = "product_definition_id")
    )
    @Column(name = "party_type", nullable = false, length = 20)
    private Set<String> eligiblePartyTypes = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "credit_product_required_documents",
            schema = "credit_product",
            joinColumns = @JoinColumn(name = "product_definition_id")
    )
    private Set<RequiredDocument> requiredDocuments = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "credit_product_channel_availability",
            schema = "credit_product",
            joinColumns = @JoinColumn(name = "product_definition_id")
    )
    @Column(name = "channel_type", nullable = false, length = 30)
    private Set<String> channelAvailabilities = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "credit_product_payment_frequencies",
            schema = "credit_product",
            joinColumns = @JoinColumn(name = "product_definition_id")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_frequency", nullable = false, length = 20)
    private Set<PaymentFrequency> allowedPaymentFrequencies = new LinkedHashSet<>();

    protected CreditProductDefinition() {}

    // ── Factory ───────────────────────────────────────────────────────────────

    public static CreditProductDefinition create(
            String productCode,
            int productVersion,
            ProductType productType,
            String name, String description,
            TargetAudience targetAudience, String currency,
            BigDecimal nominalRateAnnual, BigDecimal moratoriumRateAnnual,
            Integer minTerm, Integer maxTerm, Integer defaultTerm,
            BigDecimal minAmount, BigDecimal maxAmount,
            BigDecimal defaultCreditLine, BigDecimal minCreditLine, BigDecimal maxCreditLine,
            Integer amountStep, Integer termStep,
            AmortizationType amortizationType,
            PaymentFrequency defaultPaymentFrequency,
            Set<PaymentFrequency> allowedPaymentFrequencies,
            Integer minApprovalScore, ApprovalFlow defaultApprovalFlow,
            BigDecimal openingFeeRate, BigDecimal prepaymentFeeRate,
            Set<String> eligiblePartyTypes, Set<RequiredDocument> requiredDocuments,
            Set<String> channelAvailabilities,
            Capabilities capabilities
    ) {
        CreditProductDefinition d = new CreditProductDefinition();
        d.productDefinitionId      = UUID.randomUUID();
        d.productCode              = productCode;
        d.productVersion           = productVersion;
        d.productType              = productType;
        d.name                     = name;
        d.description              = description;
        d.status                   = ProductStatus.DRAFT;
        d.targetAudience           = targetAudience;
        d.currency                 = currency;
        d.nominalRateAnnual        = nominalRateAnnual;
        d.moratoriumRateAnnual     = moratoriumRateAnnual;
        d.minTerm                  = minTerm;
        d.maxTerm                  = maxTerm;
        d.defaultTerm              = defaultTerm;
        d.minAmount                = minAmount;
        d.maxAmount                = maxAmount;
        d.defaultCreditLine        = defaultCreditLine;
        d.minCreditLine            = minCreditLine;
        d.maxCreditLine            = maxCreditLine;
        d.amountStep               = amountStep;
        d.termStep                 = (termStep == null || termStep < 1) ? 1 : termStep;
        d.amortizationType         = amortizationType;
        d.defaultPaymentFrequency  = defaultPaymentFrequency;
        d.minApprovalScore         = minApprovalScore;
        d.defaultApprovalFlow      = defaultApprovalFlow;
        d.openingFeeRate           = openingFeeRate;
        d.prepaymentFeeRate        = prepaymentFeeRate;
        d.capabilities             = capabilities != null ? capabilities : Capabilities.defaultFor(productType);
        d.createdAt                = Instant.now();
        d.eligiblePartyTypes       = new LinkedHashSet<>(eligiblePartyTypes);
        d.requiredDocuments        = new LinkedHashSet<>(requiredDocuments);
        d.channelAvailabilities    = new LinkedHashSet<>(channelAvailabilities);
        d.allowedPaymentFrequencies = allowedPaymentFrequencies != null
                ? new LinkedHashSet<>(allowedPaymentFrequencies)
                : new LinkedHashSet<>();
        return d;
    }

    // ── State transitions ─────────────────────────────────────────────────────

    public void activate() {
        if (status != ProductStatus.DRAFT) {
            throw new InvalidProductTransitionException(productDefinitionId, status, ProductStatus.ACTIVE);
        }
        status = ProductStatus.ACTIVE;
        activatedAt = Instant.now();
    }

    /**
     * Retires this version — typically called on the previously ACTIVE version
     * when a new version of the same productCode is activated (PD-02).
     */
    public void retire() {
        if (status != ProductStatus.ACTIVE) {
            throw new InvalidProductTransitionException(productDefinitionId, status, ProductStatus.RETIRED);
        }
        status = ProductStatus.RETIRED;
        retiredAt = Instant.now();
    }

    public void deactivate() {
        if (status != ProductStatus.ACTIVE) {
            throw new InvalidProductTransitionException(productDefinitionId, status, ProductStatus.INACTIVE);
        }
        status = ProductStatus.INACTIVE;
    }

    public void reactivate() {
        if (status != ProductStatus.INACTIVE) {
            throw new InvalidProductTransitionException(productDefinitionId, status, ProductStatus.ACTIVE);
        }
        status = ProductStatus.ACTIVE;
        activatedAt = Instant.now();
    }

    public void deprecate() {
        if (status != ProductStatus.INACTIVE && status != ProductStatus.RETIRED) {
            throw new InvalidProductTransitionException(productDefinitionId, status, ProductStatus.DEPRECATED);
        }
        status = ProductStatus.DEPRECATED;
        deprecatedAt = Instant.now();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getProductDefinitionId()                { return productDefinitionId; }
    public String getProductCode()                      { return productCode; }
    public int getProductVersion()                      { return productVersion; }
    public ProductType getProductType()                 { return productType; }
    public ProductBehavior getBehavior()                { return productType.behavior; }
    public String getName()                             { return name; }
    public String getDescription()                      { return description; }
    public ProductStatus getStatus()                    { return status; }
    public TargetAudience getTargetAudience()           { return targetAudience; }
    public String getCurrency()                         { return currency; }
    public BigDecimal getNominalRateAnnual()            { return nominalRateAnnual; }
    public BigDecimal getMoratoriumRateAnnual()         { return moratoriumRateAnnual; }
    public Integer getMinTerm()                         { return minTerm; }
    public Integer getMaxTerm()                         { return maxTerm; }
    public Integer getDefaultTerm()                     { return defaultTerm; }
    public BigDecimal getMinAmount()                    { return minAmount; }
    public BigDecimal getMaxAmount()                    { return maxAmount; }
    public BigDecimal getDefaultCreditLine()            { return defaultCreditLine; }
    public BigDecimal getMinCreditLine()                { return minCreditLine; }
    public BigDecimal getMaxCreditLine()                { return maxCreditLine; }
    public Integer getAmountStep()                      { return amountStep; }
    public Integer getTermStep()                        { return termStep; }

    /**
     * Returns true if the given amount is a valid multiple of {@code amountStep}.
     * Always returns true when amountStep is null (no constraint).
     */
    public boolean isValidAmount(BigDecimal amount) {
        if (amountStep == null || amountStep <= 0 || amount == null) return true;
        BigDecimal step = BigDecimal.valueOf(amountStep);
        return amount.remainder(step).compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * Rounds the given amount down to the nearest multiple of {@code amountStep}.
     * Returns the original amount unchanged when amountStep is null.
     */
    public BigDecimal roundDownToStep(BigDecimal amount) {
        if (amountStep == null || amountStep <= 0 || amount == null) return amount;
        BigDecimal step = BigDecimal.valueOf(amountStep);
        return amount.divideToIntegralValue(step).multiply(step);
    }
    public AmortizationType getAmortizationType()       { return amortizationType; }
    public PaymentFrequency getDefaultPaymentFrequency(){ return defaultPaymentFrequency; }
    public Integer getMinApprovalScore()                { return minApprovalScore; }
    public ApprovalFlow getDefaultApprovalFlow()        { return defaultApprovalFlow; }
    public BigDecimal getOpeningFeeRate()               { return openingFeeRate; }
    public BigDecimal getPrepaymentFeeRate()            { return prepaymentFeeRate; }
    public Capabilities getCapabilities()               { return capabilities; }
    public Instant getCreatedAt()                       { return createdAt; }
    public Instant getActivatedAt()                     { return activatedAt; }
    public Instant getRetiredAt()                       { return retiredAt; }
    public Instant getDeprecatedAt()                    { return deprecatedAt; }
    public Long getVersion()                            { return version; }

    public Set<String> getEligiblePartyTypes()                { return Collections.unmodifiableSet(eligiblePartyTypes); }
    public Set<RequiredDocument> getRequiredDocuments()       { return Collections.unmodifiableSet(requiredDocuments); }
    public Set<String> getChannelAvailabilities()             { return Collections.unmodifiableSet(channelAvailabilities); }
    public Set<PaymentFrequency> getAllowedPaymentFrequencies(){ return Collections.unmodifiableSet(allowedPaymentFrequencies); }
}
