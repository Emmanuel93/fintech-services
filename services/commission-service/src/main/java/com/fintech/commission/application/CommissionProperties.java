package com.fintech.commission.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "fintech.commission")
public class CommissionProperties {

    /** LB-03: monto mínimo para procesar un LiquidationBatch. */
    private BigDecimal minLiquidationAmount = new BigDecimal("500");

    /** Día del mes en que corre la liquidación mensual. */
    private int liquidationDayOfMonth = 5;

    public BigDecimal getMinLiquidationAmount()      { return minLiquidationAmount; }
    public void setMinLiquidationAmount(BigDecimal v)  { this.minLiquidationAmount = v; }
    public int getLiquidationDayOfMonth()            { return liquidationDayOfMonth; }
    public void setLiquidationDayOfMonth(int v)       { this.liquidationDayOfMonth = v; }
}
