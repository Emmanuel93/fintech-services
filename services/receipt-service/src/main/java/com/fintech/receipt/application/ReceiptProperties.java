package com.fintech.receipt.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** How receipts are generated, who issues them and in which brand. */
@ConfigurationProperties(prefix = "fintech.receipt")
public class ReceiptProperties {

    /** This replica's name, written on what it generates. */
    private String nodeId = "local";
    /** Partitions of the receipt topics (the cutoff's, shared with statement-service). */
    private int partitions = 12;
    /** Lines processed at once by this replica. */
    private int concurrency = 4;
    private int maxAttempts = 5;
    /** A receipt GENERATING longer than this is taken by another replica: its owner died mid-way. */
    private Duration staleAfter = Duration.ofMinutes(10);

    private final Issuer issuer = new Issuer();
    private final Branding branding = new Branding();

    public String getNodeId()                 { return nodeId; }
    public void setNodeId(String v)           { this.nodeId = v; }
    public int getPartitions()                { return partitions; }
    public void setPartitions(int v)          { this.partitions = v; }
    public int getConcurrency()               { return concurrency; }
    public void setConcurrency(int v)         { this.concurrency = v; }
    public int getMaxAttempts()               { return maxAttempts; }
    public void setMaxAttempts(int v)         { this.maxAttempts = v; }
    public Duration getStaleAfter()           { return staleAfter; }
    public void setStaleAfter(Duration v)     { this.staleAfter = v; }
    public Issuer getIssuer()                 { return issuer; }
    public Branding getBranding()             { return branding; }

    /** The brand printed on the receipt: the logotype and the palette by role (defaults: the design system's). */
    public static class Branding {
        private String logo = "classpath:branding/kredius-logotipo-azul.png";
        private Map<String, String> palette = new LinkedHashMap<>();

        public String getLogo()                       { return logo; }
        public void setLogo(String v)                 { this.logo = v; }
        public Map<String, String> getPalette()       { return palette; }
        public void setPalette(Map<String, String> v) { this.palette = v; }
    }

    /** Who issues the receipt and where the client goes with a question or a complaint. */
    public static class Issuer {
        private String name = "Kredius";
        private String legalName = "Fintech Services, S.A. de C.V., SOFOM, E.N.R.";
        private String rfc = "FSE200101AB1";
        private String address = "domicilio por configurar";
        private String phone = "teléfono por configurar";
        private String website = "la app Kredius";
        private String uneAddress = "domicilio de la UNE por configurar";
        private String uneEmail = "une@kredius.mx";
        private String unePhone = "teléfono de la UNE por configurar";
        private String condusefWebsite = "www.condusef.gob.mx";
        private String condusefPhone = "55 5340 0999";

        public String getName()                  { return name; }
        public void setName(String v)            { this.name = v; }
        public String getLegalName()             { return legalName; }
        public void setLegalName(String v)       { this.legalName = v; }
        public String getRfc()                   { return rfc; }
        public void setRfc(String v)             { this.rfc = v; }
        public String getAddress()               { return address; }
        public void setAddress(String v)         { this.address = v; }
        public String getPhone()                 { return phone; }
        public void setPhone(String v)           { this.phone = v; }
        public String getWebsite()               { return website; }
        public void setWebsite(String v)         { this.website = v; }
        public String getUneAddress()            { return uneAddress; }
        public void setUneAddress(String v)      { this.uneAddress = v; }
        public String getUneEmail()              { return uneEmail; }
        public void setUneEmail(String v)        { this.uneEmail = v; }
        public String getUnePhone()              { return unePhone; }
        public void setUnePhone(String v)        { this.unePhone = v; }
        public String getCondusefWebsite()       { return condusefWebsite; }
        public void setCondusefWebsite(String v) { this.condusefWebsite = v; }
        public String getCondusefPhone()         { return condusefPhone; }
        public void setCondusefPhone(String v)   { this.condusefPhone = v; }
    }
}
