package com.fintech.invoicing.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fintech.invoicing")
public class InvoicingProperties {
    private String serie = "A";
    /** RFC genérico "público en general" (SAT) cuando el party no tiene perfil fiscal. */
    private String genericRfc = "XAXX010101000";

    public String getSerie()          { return serie; }
    public void setSerie(String v)     { this.serie = v; }
    public String getGenericRfc()     { return genericRfc; }
    public void setGenericRfc(String v){ this.genericRfc = v; }
}
