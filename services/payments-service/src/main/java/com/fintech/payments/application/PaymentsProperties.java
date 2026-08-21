package com.fintech.payments.application;

import com.fintech.payments.domain.OverpaymentStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "fintech.payments")
public class PaymentsProperties {

    /** Maximum payment amount allowed in a single transaction (0 = unlimited). */
    private BigDecimal maxPaymentAmount = BigDecimal.ZERO;

    /**
     * How long after confirmation a payment can be reversed (calendar hours).
     * Configurable via T5 (configuration-service). Default: 72h.
     */
    private int reversalWindowHours = 72;

    /**
     * What to do when the submitted amount exceeds totalDebt.
     * Configurable via T5 (configuration-service). Default: RETURN_TO_PAYER.
     */
    private OverpaymentStrategy overpaymentStrategy = OverpaymentStrategy.RETURN_TO_PAYER;

    public BigDecimal getMaxPaymentAmount()              { return maxPaymentAmount; }
    public void setMaxPaymentAmount(BigDecimal v)        { this.maxPaymentAmount = v; }

    public int getReversalWindowHours()                  { return reversalWindowHours; }
    public void setReversalWindowHours(int v)            { this.reversalWindowHours = v; }

    public OverpaymentStrategy getOverpaymentStrategy()           { return overpaymentStrategy; }
    public void setOverpaymentStrategy(OverpaymentStrategy v)     { this.overpaymentStrategy = v; }
}
