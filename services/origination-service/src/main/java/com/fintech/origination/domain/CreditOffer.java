package com.fintech.origination.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Snapshot of the pricing terms offered to the prospect (Phase E — OM-02, OM-04).
 * Embedded in {@link CreditApplication}. Immutable once accepted (CM-04).
 */
@Embeddable
public class CreditOffer {

    @Column(name = "product_code")
    private String productCode;

    /** Catalog version this offer was built from — frozen so portfolio pins the same config version. */
    @Column(name = "product_version")
    private Integer productVersion;

    /** INSTALLMENT | REVOLVING — governs CAT formula and portfolio motor. */
    @Column(name = "product_behavior")
    private String productBehavior;

    @Column(name = "offer_amortization_type")
    private String amortizationType;

    /** Approved principal — null for revolving products. OM-02: ≤ requestedAmount. */
    @Column(name = "offered_amount")
    private BigDecimal offeredAmount;

    /** Credit limit — null for installment products. */
    @Column(name = "offered_line")
    private BigDecimal offeredLine;

    /** Assigned term in months — null for revolving. */
    @Column(name = "offered_term")
    private Integer offeredTerm;

    @Column(name = "nominal_rate")
    private BigDecimal nominalRate;

    @Column(name = "moratorium_rate")
    private BigDecimal moratoriumRate;

    @Column(name = "opening_fee_rate")
    private BigDecimal openingFeeRate;

    /** Costo Anual Total (BdM Circular 21/2009 simplified). */
    @Column(name = "cat")
    private BigDecimal cat;

    /** OM-04: offer expires at this timestamp. */
    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "offer_presented_at")
    private Instant presentedAt;

    @Column(name = "offer_accepted_at")
    private Instant acceptedAt;

    protected CreditOffer() {}

    public static CreditOffer create(
            String productCode,
            Integer productVersion,
            String productBehavior,
            String amortizationType,
            BigDecimal offeredAmount,
            BigDecimal offeredLine,
            Integer offeredTerm,
            BigDecimal nominalRate,
            BigDecimal moratoriumRate,
            BigDecimal openingFeeRate,
            BigDecimal cat,
            Instant validUntil) {

        CreditOffer o = new CreditOffer();
        o.productCode      = productCode;
        o.productVersion   = productVersion;
        o.productBehavior  = productBehavior;
        o.amortizationType = amortizationType;
        o.offeredAmount    = offeredAmount;
        o.offeredLine      = offeredLine;
        o.offeredTerm      = offeredTerm;
        o.nominalRate      = nominalRate;
        o.moratoriumRate   = moratoriumRate;
        o.openingFeeRate   = openingFeeRate;
        o.cat              = cat;
        o.validUntil       = validUntil;
        o.presentedAt      = Instant.now();
        return o;
    }

    /** Called when the prospect accepts the offer. */
    void markAccepted() {
        this.acceptedAt = Instant.now();
    }

    public boolean isExpired() {
        return validUntil != null && Instant.now().isAfter(validUntil);
    }

    public String getProductCode()       { return productCode; }
    public Integer getProductVersion()   { return productVersion; }
    public String getProductBehavior()   { return productBehavior; }
    public String getAmortizationType()  { return amortizationType; }
    public BigDecimal getOfferedAmount() { return offeredAmount; }
    public BigDecimal getOfferedLine()   { return offeredLine; }
    public Integer getOfferedTerm()      { return offeredTerm; }
    public BigDecimal getNominalRate()   { return nominalRate; }
    public BigDecimal getMoratoriumRate(){ return moratoriumRate; }
    public BigDecimal getOpeningFeeRate(){ return openingFeeRate; }
    public BigDecimal getCat()           { return cat; }
    public Instant getValidUntil()       { return validUntil; }
    public Instant getPresentedAt()      { return presentedAt; }
    public Instant getAcceptedAt()       { return acceptedAt; }
}
