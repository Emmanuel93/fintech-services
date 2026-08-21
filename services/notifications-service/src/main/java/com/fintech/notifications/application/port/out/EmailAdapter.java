package com.fintech.notifications.application.port.out;

/**
 * Puerto de salida hacia el proveedor de email. Implementación v1: {@code SmtpEmailAdapter} (real,
 * vía JavaMailSender — funciona con cualquier SMTP gratuito/barato: Gmail en dev, Brevo/Resend
 * free tier, o SES en producción — deshabilitado por default, ver
 * {@code fintech.notifications.email.smtp-enabled}) con fallback a {@code NoopEmailAdapter}.
 */
public interface EmailAdapter {
    boolean send(String to, String subject, String body);
}
