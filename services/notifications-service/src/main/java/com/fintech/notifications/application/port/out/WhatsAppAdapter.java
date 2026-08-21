package com.fintech.notifications.application.port.out;

/**
 * Puerto de salida hacia la WhatsApp Cloud API de Meta (oficial, 1,000 conversaciones/mes
 * gratis directo de Meta — sin BSP intermediario). Implementación v1: {@code NoopWhatsAppAdapter}.
 */
public interface WhatsAppAdapter {
    boolean send(String phoneNumber, String message);
}
