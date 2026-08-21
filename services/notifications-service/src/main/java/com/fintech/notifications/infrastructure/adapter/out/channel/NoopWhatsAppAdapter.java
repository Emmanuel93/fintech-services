package com.fintech.notifications.infrastructure.adapter.out.channel;

import com.fintech.notifications.application.port.out.WhatsAppAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stub — confirma envío inmediato. Integración real: WhatsApp Cloud API oficial de Meta (1,000
 * conversaciones/mes gratis, directo — sin BSP intermediario como Twilio/Infobip, que solo revenden
 * lo mismo con markup). No se recomienda ninguna librería de automatización no oficial de WhatsApp
 * Web — viola los términos de servicio de Meta y arriesga el baneo del número.
 */
@Component
public class NoopWhatsAppAdapter implements WhatsAppAdapter {

    private static final Logger log = LoggerFactory.getLogger(NoopWhatsAppAdapter.class);

    @Override
    public boolean send(String phoneNumber, String message) {
        log.info("[NOOP WHATSAPP] to={} message={}", phoneNumber, message);
        return true;
    }
}
