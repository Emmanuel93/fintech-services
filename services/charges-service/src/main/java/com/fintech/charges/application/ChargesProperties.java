package com.fintech.charges.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "fintech.charges")
public class ChargesProperties {

    private BigDecimal vatRate = new BigDecimal("0.16");

    private int gracePeriodDays = 3;

    // Multiplier applied to nominalRate when moratoriumRate is absent from the activation event.
    // LGTYOC cap: moratoriumRate ≤ 2 × nominalRate — default 1.5 is conservative.
    private BigDecimal moratoriumRateMultiplier = new BigDecimal("1.5");

    private BigDecimal openingFeeRate = BigDecimal.ZERO;

    private BigDecimal prepaymentFeeRate = BigDecimal.ZERO;

    private int prepaymentFreeMonths = 0;

    private boolean adminFeeEnabled = false;

    public BigDecimal getVatRate()                   { return vatRate; }
    public void setVatRate(BigDecimal v)             { this.vatRate = v; }
    public int getGracePeriodDays()                  { return gracePeriodDays; }
    public void setGracePeriodDays(int v)            { this.gracePeriodDays = v; }
    public BigDecimal getMoratoriumRateMultiplier()  { return moratoriumRateMultiplier; }
    public void setMoratoriumRateMultiplier(BigDecimal v) { this.moratoriumRateMultiplier = v; }
    public BigDecimal getOpeningFeeRate()            { return openingFeeRate; }
    public void setOpeningFeeRate(BigDecimal v)      { this.openingFeeRate = v; }
    public BigDecimal getPrepaymentFeeRate()         { return prepaymentFeeRate; }
    public void setPrepaymentFeeRate(BigDecimal v)   { this.prepaymentFeeRate = v; }
    public int getPrepaymentFreeMonths()             { return prepaymentFreeMonths; }
    public void setPrepaymentFreeMonths(int v)       { this.prepaymentFreeMonths = v; }
    public boolean isAdminFeeEnabled()               { return adminFeeEnabled; }
    public void setAdminFeeEnabled(boolean v)        { this.adminFeeEnabled = v; }
}
