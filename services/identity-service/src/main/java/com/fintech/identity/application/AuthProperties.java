package com.fintech.identity.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "fintech.auth")
public class AuthProperties {

    @NotBlank
    private String privateKeyPath;

    @NotBlank
    private String publicKeyPath;

    @Min(1)
    private int accessTokenExpiryMinutes = 15;

    @Min(1)
    private int refreshTokenExpiryDays = 7;

    @Min(1)
    private int maxFailedAttempts = 5;

    @Min(1)
    private int lockoutDurationMinutes = 30;

    private long mfaThresholdAmount = 10_000L;

    @Min(1)
    private int validationCacheTtlSeconds = 60;

    @Min(1)
    private int mfaCodeWindowSize = 1;

    @Min(60)
    private int mfaPendingTtlSeconds = 300;

    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String v) { this.privateKeyPath = v; }

    public String getPublicKeyPath() { return publicKeyPath; }
    public void setPublicKeyPath(String v) { this.publicKeyPath = v; }

    public int getAccessTokenExpiryMinutes() { return accessTokenExpiryMinutes; }
    public void setAccessTokenExpiryMinutes(int v) { this.accessTokenExpiryMinutes = v; }

    public int getRefreshTokenExpiryDays() { return refreshTokenExpiryDays; }
    public void setRefreshTokenExpiryDays(int v) { this.refreshTokenExpiryDays = v; }

    public int getMaxFailedAttempts() { return maxFailedAttempts; }
    public void setMaxFailedAttempts(int v) { this.maxFailedAttempts = v; }

    public int getLockoutDurationMinutes() { return lockoutDurationMinutes; }
    public void setLockoutDurationMinutes(int v) { this.lockoutDurationMinutes = v; }

    public long getMfaThresholdAmount() { return mfaThresholdAmount; }
    public void setMfaThresholdAmount(long v) { this.mfaThresholdAmount = v; }

    public int getValidationCacheTtlSeconds() { return validationCacheTtlSeconds; }
    public void setValidationCacheTtlSeconds(int v) { this.validationCacheTtlSeconds = v; }

    public int getMfaCodeWindowSize() { return mfaCodeWindowSize; }
    public void setMfaCodeWindowSize(int v) { this.mfaCodeWindowSize = v; }

    public int getMfaPendingTtlSeconds() { return mfaPendingTtlSeconds; }
    public void setMfaPendingTtlSeconds(int v) { this.mfaPendingTtlSeconds = v; }
}
