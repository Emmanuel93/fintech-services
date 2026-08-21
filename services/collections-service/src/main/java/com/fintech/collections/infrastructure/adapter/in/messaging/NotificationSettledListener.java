package com.fintech.collections.infrastructure.adapter.in.messaging;

import com.fintech.collections.application.service.ContactLoggingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Cierra el ciclo del registro único: lo que notifications entregó o no pudo entregar acaba en el
 * expediente del caso.
 *
 * <p>Escucha los dos desenlaces porque los dos son gestión. Un mensaje que no se pudo entregar es
 * información valiosa —el teléfono está mal, el correo rebota— y esconderlo haría que el expediente
 * mostrara silencio donde hubo intentos fallidos.
 */
@Component
public class NotificationSettledListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationSettledListener.class);

    private final ContactLoggingService contactLoggingService;

    public NotificationSettledListener(ContactLoggingService contactLoggingService) {
        this.contactLoggingService = contactLoggingService;
    }

    @KafkaListener(topics = "notifications.notification-sent",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "notificationSentListenerContainerFactory")
    public void onSent(NotificationSettledPayload p) {
        log.debug("notification-sent source={} channel={}", p.sourceEventId(), p.channel());
        contactLoggingService.onNotificationSettled(
                p.sourceEventId(), p.notificationId(), p.channel(), true, p.sentAt());
    }

    @KafkaListener(topics = "notifications.notification-failed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "notificationFailedListenerContainerFactory")
    public void onFailed(NotificationSettledPayload p) {
        log.debug("notification-failed source={} channel={} reason={}",
                p.sourceEventId(), p.channel(), p.failureReason());
        contactLoggingService.onNotificationSettled(
                p.sourceEventId(), p.notificationId(), p.channel(), false, p.sentAt());
    }
}
