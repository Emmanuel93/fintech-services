package com.fintech.channelbackoffice.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * URLs de los servicios de dominio que consume el backoffice.
 *
 * <p>Están todas declaradas desde el arranque aunque las fases posteriores todavía no las usen: la
 * lista es el mapa de dependencias del BFF, y verla completa deja claro de dónde sale cada pantalla.
 */
@Validated
@ConfigurationProperties(prefix = "fintech.channel-backoffice")
public class ChannelBackofficeProperties {

    @NotBlank private String identityServiceUrl;
    @NotBlank private String partyServiceUrl;
    @NotBlank private String creditPortfolioServiceUrl;
    @NotBlank private String creditProductServiceUrl;
    @NotBlank private String originationServiceUrl;
    @NotBlank private String scoringServiceUrl;
    @NotBlank private String riskServiceUrl;
    @NotBlank private String collectionsServiceUrl;
    @NotBlank private String commissionServiceUrl;
    @NotBlank private String configurationServiceUrl;
    @NotBlank private String paymentsServiceUrl;
    @NotBlank private String chargesServiceUrl;
    @NotBlank private String walletServiceUrl;
    @NotBlank private String notificationsServiceUrl;
    @NotBlank private String accountingServiceUrl;
    @NotBlank private String invoicingServiceUrl;
    @NotBlank private String auditServiceUrl;
    @NotBlank private String salesOrgServiceUrl;
    @NotBlank private String beneficiaryServiceUrl;

    /** Segundos de vigencia de los agregados cacheados (resumen del dashboard). */
    @Min(1)
    private int summaryCacheTtlSeconds = 60;

    /** Corte por bloque en las composiciones con fan-out: un servicio lento degrada su tarjeta, no la pantalla. */
    @Min(100)
    private int fanOutTimeoutMillis = 2_000;

    /** Bitácora de acceso: registra en audit-service quién consultó/descargó qué, desde qué IP y cuándo. */
    private boolean accessAuditEnabled = true;

    /** Credencial propia del canal contra identity-service. Ver {@link ServiceClient}. */
    private final ServiceClient serviceClient = new ServiceClient();

    /**
     * Con qué se identifica el <em>canal</em> —no el empleado— ante identity-service.
     *
     * <p>Existe porque la bitácora tiene que decir quién actuó, y resolver ese nombre con el token
     * del propio empleado fallaba con 403 para todo el que no fuera ADMIN: auditar no puede depender
     * de los permisos del auditado. Con esta credencial el canal pregunta por su cuenta, con un rol
     * (SERVICE_DIRECTORY) que sólo permite leer un empleado por id.
     *
     * <p>Si falta el secreto, el canal simplemente no resuelve nombres y la bitácora guarda el UUID
     * pelado. Arrancar es más importante que adornar un registro.
     */
    public static class ServiceClient {
        private String clientId = "channel-backoffice-service";
        private String secret;

        public String getClientId() { return clientId; }
        public void setClientId(String v) { this.clientId = v; }

        public String getSecret() { return secret; }
        public void setSecret(String v) { this.secret = v; }

        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank() && secret != null && !secret.isBlank();
        }
    }

    public String getIdentityServiceUrl() { return identityServiceUrl; }
    public void setIdentityServiceUrl(String v) { this.identityServiceUrl = v; }

    public String getPartyServiceUrl() { return partyServiceUrl; }
    public void setPartyServiceUrl(String v) { this.partyServiceUrl = v; }

    public String getCreditPortfolioServiceUrl() { return creditPortfolioServiceUrl; }
    public void setCreditPortfolioServiceUrl(String v) { this.creditPortfolioServiceUrl = v; }

    public String getCreditProductServiceUrl() { return creditProductServiceUrl; }
    public void setCreditProductServiceUrl(String v) { this.creditProductServiceUrl = v; }

    public String getOriginationServiceUrl() { return originationServiceUrl; }
    public void setOriginationServiceUrl(String v) { this.originationServiceUrl = v; }

    public String getScoringServiceUrl() { return scoringServiceUrl; }
    public void setScoringServiceUrl(String v) { this.scoringServiceUrl = v; }

    public String getRiskServiceUrl() { return riskServiceUrl; }
    public void setRiskServiceUrl(String v) { this.riskServiceUrl = v; }

    public String getCollectionsServiceUrl() { return collectionsServiceUrl; }
    public void setCollectionsServiceUrl(String v) { this.collectionsServiceUrl = v; }

    public String getCommissionServiceUrl() { return commissionServiceUrl; }
    public void setCommissionServiceUrl(String v) { this.commissionServiceUrl = v; }

    public String getConfigurationServiceUrl() { return configurationServiceUrl; }
    public void setConfigurationServiceUrl(String v) { this.configurationServiceUrl = v; }

    public String getPaymentsServiceUrl() { return paymentsServiceUrl; }
    public void setPaymentsServiceUrl(String v) { this.paymentsServiceUrl = v; }

    public String getChargesServiceUrl() { return chargesServiceUrl; }
    public void setChargesServiceUrl(String v) { this.chargesServiceUrl = v; }

    public String getWalletServiceUrl() { return walletServiceUrl; }
    public void setWalletServiceUrl(String v) { this.walletServiceUrl = v; }

    public String getNotificationsServiceUrl() { return notificationsServiceUrl; }
    public void setNotificationsServiceUrl(String v) { this.notificationsServiceUrl = v; }

    public String getAccountingServiceUrl() { return accountingServiceUrl; }
    public void setAccountingServiceUrl(String v) { this.accountingServiceUrl = v; }

    public String getInvoicingServiceUrl() { return invoicingServiceUrl; }
    public void setInvoicingServiceUrl(String v) { this.invoicingServiceUrl = v; }

    public String getAuditServiceUrl() { return auditServiceUrl; }
    public void setAuditServiceUrl(String v) { this.auditServiceUrl = v; }

    public String getBeneficiaryServiceUrl() { return beneficiaryServiceUrl; }
    public void setBeneficiaryServiceUrl(String v) { this.beneficiaryServiceUrl = v; }

    public String getSalesOrgServiceUrl() { return salesOrgServiceUrl; }
    public void setSalesOrgServiceUrl(String v) { this.salesOrgServiceUrl = v; }

    public int getSummaryCacheTtlSeconds() { return summaryCacheTtlSeconds; }
    public void setSummaryCacheTtlSeconds(int v) { this.summaryCacheTtlSeconds = v; }

    public int getFanOutTimeoutMillis() { return fanOutTimeoutMillis; }
    public void setFanOutTimeoutMillis(int v) { this.fanOutTimeoutMillis = v; }

    public boolean isAccessAuditEnabled() { return accessAuditEnabled; }
    public void setAccessAuditEnabled(boolean v) { this.accessAuditEnabled = v; }

    public ServiceClient getServiceClient() { return serviceClient; }
}
