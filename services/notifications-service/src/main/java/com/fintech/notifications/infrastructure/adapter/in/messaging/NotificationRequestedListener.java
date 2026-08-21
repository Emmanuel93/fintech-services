package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fintech.notifications.application.service.RecipientNotificationService;
import com.fintech.notifications.domain.InvalidRecipientException;
import com.fintech.notifications.domain.UnknownRecipientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * El único consumidor que no sabe nada de nadie.
 *
 * <p>Los otros diez listeners de este paquete escuchan tópicos de dominio —cobranza, cartera,
 * originación— y por eso cada hecho nuevo obligaba a escribir código aquí. Éste escucha un solo
 * tópico genérico: un aviso nuevo se publica y ya, sin tocar este servicio.
 *
 * <p>Los diez conviven con éste mientras se migran. Es una migración, no un diseño: romperlos hoy
 * para ganar simetría sería cambiar valor por forma.
 */
@Component
public class NotificationRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationRequestedListener.class);

    private final RecipientNotificationService service;

    public NotificationRequestedListener(RecipientNotificationService service) {
        this.service = service;
    }

    @KafkaListener(topics = "notifications.notification-requested",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "notificationRequestedListenerContainerFactory")
    public void onMessage(NotificationRequestedPayload p) {
        if (p == null || p.recipientId() == null
                || p.recipientType() == null || p.recipientType().isBlank()
                || p.eventKey() == null || p.eventKey().isBlank()) {
            log.warn("notification-requested incompleto — se descarta: {}", p);
            return;
        }

        String sourceEventId = (p.sourceEventId() == null || p.sourceEventId().isBlank())
                ? "notif:" + p.recipientType() + ":" + p.recipientId() + ":" + p.eventKey()
                : p.sourceEventId();

        try {
            service.notify(sourceEventId, p.recipientType(), p.recipientId(), p.eventKey(),
                    p.variables() == null ? Map.of() : p.variables(), p.channels());
        } catch (UnknownRecipientException | InvalidRecipientException ex) {
            // Se descarta en vez de reintentar: un destinatario que no está registrado no va a
            // aparecer porque volvamos a intentarlo, y dejar el mensaje rebotando en el consumidor
            // bloquearía la partición para todos los avisos que sí tienen a quién llegarle.
            log.warn("Aviso descartado eventKey={} destinatario={}/{}: {}",
                    p.eventKey(), p.recipientType(), p.recipientId(), ex.getMessage());
        }
    }
}
