package com.fintech.statement.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "fintech.statement")
public class StatementProperties {

    /** Identidad del pod: queda grabada en cada documento que genera. */
    private String nodeId = "local";

    /** Particiones de {@code closing.cutoff-closed}: el techo de pods generando a la vez. */
    private int partitions = 12;

    /** Hilos consumidores por pod. Cada uno llena y exporta un PDF a la vez. */
    private int concurrency = 4;

    /** Intentos por documento antes de dejarlo FAILED para revisión. */
    private int maxAttempts = 5;

    /** Un documento GENERATING sin avance en este tiempo es de un pod que murió: se retoma. */
    private Duration staleAfter = Duration.ofMinutes(10);

    private final Issuer issuer = new Issuer();
    private final Branding branding = new Branding();

    public String getNodeId()                         { return nodeId; }
    public void setNodeId(String n)                   { this.nodeId = n; }
    public int getPartitions()                        { return partitions; }
    public void setPartitions(int p)                  { this.partitions = p; }
    public int getConcurrency()                       { return concurrency; }
    public void setConcurrency(int c)                 { this.concurrency = c; }
    public int getMaxAttempts()                       { return maxAttempts; }
    public void setMaxAttempts(int m)                 { this.maxAttempts = m; }
    public Duration getStaleAfter()                   { return staleAfter; }
    public void setStaleAfter(Duration d)             { this.staleAfter = d; }
    public Issuer getIssuer()                         { return issuer; }
    public Branding getBranding()                     { return branding; }

    /**
     * How the document looks: the logo and the palette. The defaults are the Kredius design system; the
     * templates are written with these same values and the renderer swaps them for whatever is configured.
     */
    public static class Branding {
        /** Spring resource location of the logo (PNG/JPEG). Blank or unreadable: the issuer's name is printed instead. */
        private String logo = "classpath:branding/kredius-logotipo-azul.png";
        private final Palette palette = new Palette();
        public String getLogo()                 { return logo; }
        public void setLogo(String l)           { this.logo = l; }
        public Palette getPalette()             { return palette; }
    }

    /** The color roles of the templates, as #RRGGBB. */
    public static class Palette {
        /** Azul Kredius: headings, rules, the main box. */
        private String primary = "#0D47A1";
        /** The logo's green: the one accent. */
        private String accent = "#00B894";
        /** Body text. */
        private String ink = "#0F1A2E";
        /** Secondary text. */
        private String muted = "#7A8296";
        /** Section fills (table header, totals). */
        private String paper = "#EBF0F7";
        /** Zebra rows and cards. */
        private String soft = "#F6F8FC";
        /** Dividers. */
        private String line = "#EDEFF3";
        /** The two context boxes. */
        private String primaryContainer = "#D6E4FF";
        /** Text on them. */
        private String onPrimaryContainer = "#001A42";

        /** The design system's values, in the order the roles are declared: what the templates are written with. */
        public static Palette defaults() { return new Palette(); }

        /** Role → configured color, for the substitution. */
        public java.util.Map<String, String> roles() {
            return new java.util.LinkedHashMap<>(java.util.Map.of(
                    "primary", primary, "accent", accent, "ink", ink, "muted", muted, "paper", paper,
                    "soft", soft, "line", line, "primaryContainer", primaryContainer,
                    "onPrimaryContainer", onPrimaryContainer));
        }

        public String getPrimary()                      { return primary; }
        public void setPrimary(String v)                { this.primary = v; }
        public String getAccent()                       { return accent; }
        public void setAccent(String v)                 { this.accent = v; }
        public String getInk()                          { return ink; }
        public void setInk(String v)                    { this.ink = v; }
        public String getMuted()                        { return muted; }
        public void setMuted(String v)                  { this.muted = v; }
        public String getPaper()                        { return paper; }
        public void setPaper(String v)                  { this.paper = v; }
        public String getSoft()                         { return soft; }
        public void setSoft(String v)                   { this.soft = v; }
        public String getLine()                         { return line; }
        public void setLine(String v)                   { this.line = v; }
        public String getPrimaryContainer()             { return primaryContainer; }
        public void setPrimaryContainer(String v)       { this.primaryContainer = v; }
        public String getOnPrimaryContainer()           { return onPrimaryContainer; }
        public void setOnPrimaryContainer(String v)     { this.onPrimaryContainer = v; }
    }

    /**
     * Who issues the document, and the data a SOFOM ENR statement must carry about it (CONDUSEF DT-ENR art. 35-I and
     * 35-VIII): company name with "SOFOM, E.N.R." (LGOAAC 87-B), RFC, address, phone, and where its Unidad
     * Especializada de Atención a Usuarios (UNE) takes clarifications. The address and the phones have no real default:
     * they print "por configurar" until the company's own are set (STATEMENT_ISSUER_*), never an invented one.
     */
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
        private String legend = "Estado de cuenta informativo";
        private String legalNotice = "Este documento resume los movimientos de tu crédito al cierre del periodo. "
                + "Las cifras corresponden a la fecha de corte; los movimientos posteriores aparecerán en tu "
                + "siguiente estado de cuenta.";

        public String getName()                     { return name; }
        public void setName(String n)               { this.name = n; }
        public String getLegalName()                { return legalName; }
        public void setLegalName(String v)          { this.legalName = v; }
        public String getRfc()                      { return rfc; }
        public void setRfc(String v)                { this.rfc = v; }
        public String getAddress()                  { return address; }
        public void setAddress(String v)            { this.address = v; }
        public String getPhone()                    { return phone; }
        public void setPhone(String v)              { this.phone = v; }
        public String getWebsite()                  { return website; }
        public void setWebsite(String v)            { this.website = v; }
        public String getUneAddress()               { return uneAddress; }
        public void setUneAddress(String v)         { this.uneAddress = v; }
        public String getUneEmail()                 { return uneEmail; }
        public void setUneEmail(String v)           { this.uneEmail = v; }
        public String getUnePhone()                 { return unePhone; }
        public void setUnePhone(String v)           { this.unePhone = v; }
        public String getCondusefWebsite()          { return condusefWebsite; }
        public void setCondusefWebsite(String v)    { this.condusefWebsite = v; }
        public String getCondusefPhone()            { return condusefPhone; }
        public void setCondusefPhone(String v)      { this.condusefPhone = v; }
        public String getLegend()                   { return legend; }
        public void setLegend(String l)             { this.legend = l; }
        public String getLegalNotice()              { return legalNotice; }
        public void setLegalNotice(String l)        { this.legalNotice = l; }
    }
}
