package com.fintech.risk.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fintech.risk")
public class RiskProperties {

    /** ES-01: días de mora a partir de los cuales una cuenta entra a STAGE_2 (SICR backstop). */
    private int stage2DaysThreshold = 30;

    /** ES-01: días de mora a partir de los cuales una cuenta entra a STAGE_3 (default, 90d IFRS-9). */
    private int stage3DaysThreshold = 90;

    /** RC-04: meses de cura tras una reestructura antes de poder bajar de STAGE_2. */
    private int cureMonths = 6;
    /** Days an account must keep qualifying for a better stage before leaving Stage 3 (one step: to Stage 2). */
    private int stage3ProbationDays = 90;
    /** Days before leaving Stage 2 for Stage 1. */
    private int stage2ProbationDays = 30;

    public int getStage2DaysThreshold()          { return stage2DaysThreshold; }
    public void setStage2DaysThreshold(int v)     { this.stage2DaysThreshold = v; }
    public int getStage3DaysThreshold()          { return stage3DaysThreshold; }
    public void setStage3DaysThreshold(int v)     { this.stage3DaysThreshold = v; }
    public int getCureMonths()                   { return cureMonths; }
    public void setCureMonths(int v)              { this.cureMonths = v; }

    public int getStage3ProbationDays()        { return stage3ProbationDays; }
    public void setStage3ProbationDays(int d)  { this.stage3ProbationDays = d; }
    public int getStage2ProbationDays()        { return stage2ProbationDays; }
    public void setStage2ProbationDays(int d)  { this.stage2ProbationDays = d; }
}
