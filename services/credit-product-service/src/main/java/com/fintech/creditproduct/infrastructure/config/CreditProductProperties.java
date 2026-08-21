package com.fintech.creditproduct.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Registered via {@code @EnableConfigurationProperties} in CreditProductModuleConfig. */
@ConfigurationProperties(prefix = "fintech.credit-product")
public class CreditProductProperties {

    private String jwtSecret;

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String v) { this.jwtSecret = v; }
}
