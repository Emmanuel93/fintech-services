package com.fintech.channels.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "fintech.channels")
@Validated
public class ChannelsProperties {

    private int defaultSessionTtlMinutes = 30;
    private int defaultMaxIdleMinutes = 10;
    private int leadTtlDaysDigital = 30;
    private int leadTtlDaysPromoter = 7;
    private String partyServiceUrl = "http://party-service:8080";

    public int getDefaultSessionTtlMinutes() { return defaultSessionTtlMinutes; }
    public void setDefaultSessionTtlMinutes(int v) { this.defaultSessionTtlMinutes = v; }

    public int getDefaultMaxIdleMinutes() { return defaultMaxIdleMinutes; }
    public void setDefaultMaxIdleMinutes(int v) { this.defaultMaxIdleMinutes = v; }

    public int getLeadTtlDaysDigital() { return leadTtlDaysDigital; }
    public void setLeadTtlDaysDigital(int v) { this.leadTtlDaysDigital = v; }

    public int getLeadTtlDaysPromoter() { return leadTtlDaysPromoter; }
    public void setLeadTtlDaysPromoter(int v) { this.leadTtlDaysPromoter = v; }

    public String getPartyServiceUrl() { return partyServiceUrl; }
    public void setPartyServiceUrl(String v) { this.partyServiceUrl = v; }
}
