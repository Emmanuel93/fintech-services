package com.fintech.commission.application;

import com.fintech.commission.domain.TaxRules;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "fintech.commission")
public class CommissionProperties {

    /** LB-03: monto mínimo (comisión antes de IVA) para abrir un lote; lo que no llega se arrastra al siguiente. */
    private BigDecimal minLiquidationAmount = new BigDecimal("500");

    /** Día del mes en que corre la liquidación mensual. */
    private int liquidationDayOfMonth = 5;

    /** IVA que la beneficiaria traslada sobre la comisión. */
    private BigDecimal ivaRate = TaxRules.MEXICO.ivaRate();

    /** ISR que se retiene a una persona física. */
    private BigDecimal isrNaturalPersonRate = TaxRules.MEXICO.isrNaturalPersonRate();

    /** ISR que se retiene a una persona física en el Régimen Simplificado de Confianza. */
    private BigDecimal isrResicoRate = TaxRules.MEXICO.isrResicoRate();

    /** Parte del IVA que se retiene a una persona física. */
    private BigDecimal ivaWithheldFraction = TaxRules.MEXICO.ivaWithheldFraction();

    public TaxRules taxRules() {
        return new TaxRules(ivaRate, isrNaturalPersonRate, isrResicoRate, ivaWithheldFraction);
    }

    public BigDecimal getMinLiquidationAmount()       { return minLiquidationAmount; }
    public void setMinLiquidationAmount(BigDecimal v) { this.minLiquidationAmount = v; }
    public int getLiquidationDayOfMonth()             { return liquidationDayOfMonth; }
    public void setLiquidationDayOfMonth(int v)       { this.liquidationDayOfMonth = v; }
    public BigDecimal getIvaRate()                    { return ivaRate; }
    public void setIvaRate(BigDecimal v)              { this.ivaRate = v; }
    public BigDecimal getIsrNaturalPersonRate()       { return isrNaturalPersonRate; }
    public void setIsrNaturalPersonRate(BigDecimal v) { this.isrNaturalPersonRate = v; }
    public BigDecimal getIsrResicoRate()              { return isrResicoRate; }
    public void setIsrResicoRate(BigDecimal v)        { this.isrResicoRate = v; }
    public BigDecimal getIvaWithheldFraction()        { return ivaWithheldFraction; }
    public void setIvaWithheldFraction(BigDecimal v)  { this.ivaWithheldFraction = v; }
}
