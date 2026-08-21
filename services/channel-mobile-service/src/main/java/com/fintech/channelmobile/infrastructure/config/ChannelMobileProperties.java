package com.fintech.channelmobile.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fintech.channel-mobile")
public class ChannelMobileProperties {

    private String identityServiceUrl = "http://localhost:8082";
    private String originationServiceUrl = "http://localhost:8081";
    private String creditProductServiceUrl = "http://localhost:8084";
    private String partyServiceUrl = "http://localhost:8083";
    private String creditPortfolioServiceUrl = "http://localhost:8087";
    private String walletServiceUrl = "http://localhost:8092";
    private String notificationsServiceUrl = "http://localhost:8098";
    private String paymentsServiceUrl = "http://localhost:8089";
    private String chargesServiceUrl = "http://localhost:8088";
    private String scoringServiceUrl = "http://localhost:8082";
    /**
     * Bitácora regulatoria.
     *
     * <p>El canal móvil no auditaba nada: toda la actividad de clientes y distribuidores era
     * invisible para una revisión. La pregunta «quién consultó a este cliente» sólo podía
     * contestarse sobre el backoffice, como si la app no existiera.
     */
    private String auditServiceUrl = "http://localhost:8099";
    private String beneficiaryServiceUrl = "http://localhost:8102";
    /** Endpoints /internal/test-support/* del BFF — solo para pruebas E2E automatizadas. */
    private boolean testSupportEnabled = false;
    private int otpExpiryMinutes = 5;
    private int otpMaxAttempts = 3;
    private int otpMaxSendsPerWindow = 5;
    private int otpSendWindowMinutes = 60;
    private int kycSessionExpiryMinutes = 30;
    /** true = valida OTP real generado; false = valida contra otpDevCode (ambientes bajos). */
    private boolean otpCodeValidation = true;
    /** Código OTP fijo para ambientes bajos. Solo aplica cuando otpCodeValidation=false. */
    private String otpDevCode;

    public String getAuditServiceUrl() { return auditServiceUrl; }
    public void setAuditServiceUrl(String v) { this.auditServiceUrl = v; }
    public String getBeneficiaryServiceUrl() { return beneficiaryServiceUrl; }
    public void setBeneficiaryServiceUrl(String v) { this.beneficiaryServiceUrl = v; }

    public String getIdentityServiceUrl() { return identityServiceUrl; }
    public void setIdentityServiceUrl(String v) { this.identityServiceUrl = v; }

    public String getOriginationServiceUrl() { return originationServiceUrl; }
    public void setOriginationServiceUrl(String v) { this.originationServiceUrl = v; }

    public String getCreditProductServiceUrl() { return creditProductServiceUrl; }
    public void setCreditProductServiceUrl(String v) { this.creditProductServiceUrl = v; }

    public String getPartyServiceUrl() { return partyServiceUrl; }
    public void setPartyServiceUrl(String v) { this.partyServiceUrl = v; }

    public String getCreditPortfolioServiceUrl() { return creditPortfolioServiceUrl; }
    public void setCreditPortfolioServiceUrl(String v) { this.creditPortfolioServiceUrl = v; }

    public String getWalletServiceUrl() { return walletServiceUrl; }
    public void setWalletServiceUrl(String v) { this.walletServiceUrl = v; }

    public String getNotificationsServiceUrl() { return notificationsServiceUrl; }
    public void setNotificationsServiceUrl(String v) { this.notificationsServiceUrl = v; }

    public String getPaymentsServiceUrl() { return paymentsServiceUrl; }
    public void setPaymentsServiceUrl(String v) { this.paymentsServiceUrl = v; }

    public String getChargesServiceUrl() { return chargesServiceUrl; }
    public void setChargesServiceUrl(String v) { this.chargesServiceUrl = v; }

    public String getScoringServiceUrl() { return scoringServiceUrl; }
    public void setScoringServiceUrl(String v) { this.scoringServiceUrl = v; }

    public boolean isTestSupportEnabled() { return testSupportEnabled; }
    public void setTestSupportEnabled(boolean v) { this.testSupportEnabled = v; }

    public int getOtpExpiryMinutes() { return otpExpiryMinutes; }
    public void setOtpExpiryMinutes(int v) { this.otpExpiryMinutes = v; }

    public int getOtpMaxAttempts() { return otpMaxAttempts; }
    public void setOtpMaxAttempts(int v) { this.otpMaxAttempts = v; }

    public int getOtpMaxSendsPerWindow() { return otpMaxSendsPerWindow; }
    public void setOtpMaxSendsPerWindow(int v) { this.otpMaxSendsPerWindow = v; }

    public int getOtpSendWindowMinutes() { return otpSendWindowMinutes; }
    public void setOtpSendWindowMinutes(int v) { this.otpSendWindowMinutes = v; }

    public int getKycSessionExpiryMinutes() { return kycSessionExpiryMinutes; }
    public void setKycSessionExpiryMinutes(int v) { this.kycSessionExpiryMinutes = v; }

    public boolean isOtpCodeValidation() { return otpCodeValidation; }
    public void setOtpCodeValidation(boolean v) { this.otpCodeValidation = v; }

    public String getOtpDevCode() { return otpDevCode; }
    public void setOtpDevCode(String v) { this.otpDevCode = v; }
}
