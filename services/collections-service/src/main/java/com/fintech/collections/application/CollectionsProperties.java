package com.fintech.collections.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "fintech.collections")
public class CollectionsProperties {

    /** ES-03: daysDelinquent threshold from which an account is a write-off candidate. */
    private int writeOffThresholdDays = 181;

    /** CT-03 (CONDUSEF). */
    private int maxContactAttemptsPerDay = 3;

    /** CT-02 (CONDUSEF) — allowed contact window, 24h clock. */
    private int contactAllowedHoursStart = 8;
    private int contactAllowedHoursEnd = 20;

    /** EC-01 — days before dueDate that UpcomingInstallmentJob (credit-portfolio) fires. */
    private int reminderLeadDays = 3;

    /** AG-05 — days a PROPOSED agreement waits for a debtor response before EXPIRED. */
    private int agreementResponseDays = 5;

    /** AG-08 — max fraction of originalDebt that can be forgiven in a QUITA_PARCIAL. */
    private BigDecimal maxForgivenessPct = new BigDecimal("0.30");

    /** AG-09 — max months a RESTRUCTURE can extend the term. */
    private int maxTermExtensionMonths = 12;

    // ── Cadencia automática ──────────────────────────────────────────────────

    /**
     * Si la cadencia automática está encendida. Apagada, el resto del módulo funciona igual: la
     * gestión manual no depende de ella y los frenos se siguen registrando.
     */
    private boolean dunningEnabled = true;

    /**
     * Días de gracia tras la fecha prometida antes de dar la promesa por rota.
     *
     * <p>Un pago hecho el mismo día puede tardar en aplicarse; romper la promesa a medianoche
     * castigaría a quien sí pagó por la latencia del sistema de pagos.
     */
    private int promiseGraceDays = 2;

    /**
     * Silencio que se concede tras una promesa cumplida, si el caso sigue con saldo vencido.
     *
     * <p>Cumplir y recibir al día siguiente el mismo recordatorio que antes de pagar es la forma
     * más rápida de enseñarle a alguien que cumplir no cambia nada.
     */
    private int promiseKeptQuietDays = 7;

    /** Silencio que suma un abono parcial: no cumple la promesa, pero tampoco merece el mismo trato. */
    private int partialPaymentQuietDays = 3;

    public boolean isDunningEnabled()                 { return dunningEnabled; }
    public void setDunningEnabled(boolean v)          { this.dunningEnabled = v; }
    public int getPromiseGraceDays()                  { return promiseGraceDays; }
    public void setPromiseGraceDays(int v)            { this.promiseGraceDays = v; }
    public int getPromiseKeptQuietDays()              { return promiseKeptQuietDays; }
    public void setPromiseKeptQuietDays(int v)        { this.promiseKeptQuietDays = v; }
    public int getPartialPaymentQuietDays()           { return partialPaymentQuietDays; }
    public void setPartialPaymentQuietDays(int v)     { this.partialPaymentQuietDays = v; }

    public int getWriteOffThresholdDays()            { return writeOffThresholdDays; }
    public void setWriteOffThresholdDays(int v)       { this.writeOffThresholdDays = v; }
    public int getMaxContactAttemptsPerDay()          { return maxContactAttemptsPerDay; }
    public void setMaxContactAttemptsPerDay(int v)    { this.maxContactAttemptsPerDay = v; }
    public int getContactAllowedHoursStart()          { return contactAllowedHoursStart; }
    public void setContactAllowedHoursStart(int v)    { this.contactAllowedHoursStart = v; }
    public int getContactAllowedHoursEnd()            { return contactAllowedHoursEnd; }
    public void setContactAllowedHoursEnd(int v)      { this.contactAllowedHoursEnd = v; }
    public int getReminderLeadDays()                  { return reminderLeadDays; }
    public void setReminderLeadDays(int v)            { this.reminderLeadDays = v; }
    public int getAgreementResponseDays()             { return agreementResponseDays; }
    public void setAgreementResponseDays(int v)       { this.agreementResponseDays = v; }
    public BigDecimal getMaxForgivenessPct()          { return maxForgivenessPct; }
    public void setMaxForgivenessPct(BigDecimal v)    { this.maxForgivenessPct = v; }
    public int getMaxTermExtensionMonths()            { return maxTermExtensionMonths; }
    public void setMaxTermExtensionMonths(int v)      { this.maxTermExtensionMonths = v; }
}
