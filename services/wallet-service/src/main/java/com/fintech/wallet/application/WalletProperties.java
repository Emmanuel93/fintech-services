package com.fintech.wallet.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "fintech.wallet")
@Validated
public class WalletProperties {

    // WP-05: minutes after lastUpdatedAt before WalletView is considered STALE
    @Min(1)
    private int staleThresholdMinutes = 30;

    // PI-04: CoDi token TTL in minutes per T5 config
    @Min(1)
    private int codiExpiryMinutes = 10;

    // IP-04: SPEI operating window start hour (Mexico time, 24h)
    @Min(0)
    private int speiStartHour = 7;

    // IP-04: SPEI operating window end hour
    @Min(0)
    private int speiEndHour = 23;

    @NotNull
    private String speiTimeZone = "America/Mexico_City";

    public int getStaleThresholdMinutes()  { return staleThresholdMinutes; }
    public void setStaleThresholdMinutes(int v) { this.staleThresholdMinutes = v; }
    public int getCodiExpiryMinutes()      { return codiExpiryMinutes; }
    public void setCodiExpiryMinutes(int v) { this.codiExpiryMinutes = v; }
    public int getSpeiStartHour()          { return speiStartHour; }
    public void setSpeiStartHour(int v)    { this.speiStartHour = v; }
    public int getSpeiEndHour()            { return speiEndHour; }
    public void setSpeiEndHour(int v)      { this.speiEndHour = v; }
    public String getSpeiTimeZone()        { return speiTimeZone; }
    public void setSpeiTimeZone(String v)  { this.speiTimeZone = v; }
}
