package com.fintech.notifications.infrastructure.adapter.out.channel;

import com.fintech.notifications.application.port.out.PushNotificationAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stub de notificación push — confirma el envío de inmediato.
 *
 * <p>El comentario anterior lo emparentaba con {@code NoopSpeiDispatchAdapter}, que se eliminó en
 * BK-11. La diferencia importa: aquél confirmaba <b>un pago</b> que nunca salía y hacía que el mayor
 * asentara una salida de caja inexistente. Éste confirma un aviso, y el peor caso es que alguien no
 * reciba una notificación. Cuando este canal se implemente de verdad, el sustituto debe comportarse
 * como el proveedor —incluido fallar—, no como un no-op.
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
