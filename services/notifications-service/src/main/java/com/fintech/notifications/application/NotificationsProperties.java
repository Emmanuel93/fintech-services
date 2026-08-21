package com.fintech.notifications.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fintech.notifications")
public class NotificationsProperties {

    private String defaultLocale = "es-MX";
    private Email email = new Email();

    public String getDefaultLocale()     { return defaultLocale; }
    public void setDefaultLocale(String v) { this.defaultLocale = v; }
    public Email getEmail()               { return email; }
    public void setEmail(Email v)          { this.email = v; }

    public static class Email {
        /** SMTP real (Brevo/Resend free tier, SES, Gmail en dev) — false = usa NoopEmailAdapter. */
        private boolean smtpEnabled = false;
        private String fromAddress = "no-reply@fintech.local";

        public boolean isSmtpEnabled()      { return smtpEnabled; }
        public void setSmtpEnabled(boolean v) { this.smtpEnabled = v; }
        public String getFromAddress()       { return fromAddress; }
        public void setFromAddress(String v)  { this.fromAddress = v; }
    }
}
