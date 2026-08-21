package com.fintech.notifications.infrastructure.adapter.out.channel;

import com.fintech.notifications.application.port.out.PushNotificationAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stub — confirma envío inmediato, mismo patrón que NoopSpeiDispatchAdapter/NoopPacAdapter en el
 * resto del sistema. Integración real (gratuita, sin costo por volumen): Firebase Cloud Messaging
 * (Android/Web) + APNs (iOS).
 */
@Component
public class NoopPushAdapter implements PushNotificationAdapter {

    private static final Logger log = LoggerFactory.getLogger(NoopPushAdapter.class);

    @Override
    public boolean send(String pushToken, String title, String body) {
        log.info("[NOOP PUSH] token={} title={} body={}", pushToken, title, body);
        return true;
    }
}
