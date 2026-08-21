package com.fintech.accounting.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fintech.accounting")
public class AccountingProperties {

    private String currency = "MXN";

    /** Día del mes en que el BillingRunJob consolida la facturación del período anterior. */
    private int billingDayOfMonth = 1;

    /**
     * La zona con la que se decide a qué mes pertenece un hecho.
     *
     * <p>No es cosmética: en UTC, un hecho de las 19:00 del 31 de agosto en Ciudad de México ya es
     * septiembre, y el corte mensual dejaría de coincidir con el día natural que usa negocio. Va en
     * configuración para que operar en otra plaza no exija desplegar.
     */
    private String zone = "America/Mexico_City";

    public String getCurrency()              { return currency; }
    public void setCurrency(String v)         { this.currency = v; }
    public int getBillingDayOfMonth()        { return billingDayOfMonth; }
    public void setBillingDayOfMonth(int v)   { this.billingDayOfMonth = v; }
    public String getZone()                  { return zone; }
    public void setZone(String v)             { this.zone = v; }

    public java.time.ZoneId zoneId() {
        return java.time.ZoneId.of(zone);
    }
}
