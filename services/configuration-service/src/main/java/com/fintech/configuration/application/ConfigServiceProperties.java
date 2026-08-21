package com.fintech.configuration.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "fintech.configuration")
@Validated
public class ConfigServiceProperties {

    @NotBlank
    private String jwtSecret;

    /** Default cache TTL in seconds for config parameters (domains can override). */
    @Positive
    private int cacheTtlSeconds = 300;

    public String getJwtSecret()              { return jwtSecret; }
    public void setJwtSecret(String s)        { this.jwtSecret = s; }
    public int getCacheTtlSeconds()           { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(int n)     { this.cacheTtlSeconds = n; }
}
