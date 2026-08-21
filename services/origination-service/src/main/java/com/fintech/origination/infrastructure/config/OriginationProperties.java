package com.fintech.origination.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "fintech.origination")
public class OriginationProperties {

    private int prospectExpiryDays = 30;
    private String creditProductServiceUrl = "http://localhost:8084";
    private String partyServiceUrl = "http://localhost:8083";
    private String salesOrgServiceUrl = "http://localhost:8100";

    /** Applications above this MXN amount go to COMMITTEE_REVIEW instead of UNDER_MANUAL_REVIEW. */
    private java.math.BigDecimal committeeAmountThreshold = new java.math.BigDecimal("500000");

    /** Days after rejection before the prospect can re-apply for the same productType (UW-06). */
    private int cooldownDays = 90;

    /** Plazo (días) para entregar documentos antes de que una solicitud en PENDING_DOCUMENTS caduque (E6). */
    private int documentsTtlDays = 15;

    public int getProspectExpiryDays() { return prospectExpiryDays; }
    public void setProspectExpiryDays(int v) { this.prospectExpiryDays = v; }

    public String getCreditProductServiceUrl() { return creditProductServiceUrl; }
    public void setCreditProductServiceUrl(String v) { this.creditProductServiceUrl = v; }

    public String getPartyServiceUrl() { return partyServiceUrl; }
    public void setPartyServiceUrl(String v) { this.partyServiceUrl = v; }

    public String getSalesOrgServiceUrl() { return salesOrgServiceUrl; }
    public void setSalesOrgServiceUrl(String v) { this.salesOrgServiceUrl = v; }

    public java.math.BigDecimal getCommitteeAmountThreshold() { return committeeAmountThreshold; }
    public void setCommitteeAmountThreshold(java.math.BigDecimal v) { this.committeeAmountThreshold = v; }

    public int getCooldownDays() { return cooldownDays; }
    public void setCooldownDays(int v) { this.cooldownDays = v; }

    public int getDocumentsTtlDays() { return documentsTtlDays; }
    public void setDocumentsTtlDays(int v) { this.documentsTtlDays = v; }
}
