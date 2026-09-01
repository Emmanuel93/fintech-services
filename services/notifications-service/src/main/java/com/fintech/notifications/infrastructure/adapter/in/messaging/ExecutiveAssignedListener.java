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
 * Le avisa al ejecutivo que un cliente es suyo.
 *
 * <p><b>La interpretación vive aquí, no en party.</b> Party publica el hecho —«este cliente pasó a
 * ser de este ejecutivo»— sin saber que alguien lo va a notificar: no menciona claves de evento,
 * tipos de destinatario ni canales. Decidir que ese hecho merece campana, para quién y con qué
 * texto es de este servicio, que es el que sabe de campanas.
 *
 * <p>Es lo contrario de lo que se hizo primero: party publicaba directamente un
 * {@code notification-requested}, con lo que el núcleo del negocio pasaba a conocer el vocabulario
 * del notificador. El carril genérico sigue existiendo, pero para lo que <b>no</b> es un hecho de
 * dominio — el backoffice pidiendo un aviso, o un sistema externo.
 *
 * <p>Un evento por asignación, porque asignar es de a uno: no existe la operación masiva, así que
 * tampoco el hecho «se te asignaron N clientes».
 */
@Component
public class ExecutiveAssignedListener {

    private static final Logger log = LoggerFactory.getLogger(ExecutiveAssignedListener.class);
    private static final String EVENT_KEY = "STAFF_CLIENTES_ASIGNADOS";

    private final RecipientNotificationService service;

    public ExecutiveAssignedListener(RecipientNotificationService service) {
        this.service = service;
    }

    @KafkaListener(topics = "party.executive-assigned",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "executiveAssignedListenerContainerFactory")
    public void onMessage(ExecutiveAssignedPayload p) {
        if (p == null || p.executiveId() == null) {
            log.warn("executive-assigned sin ejecutivo — se descarta: {}", p);
            return;
        }

        // Determinista: Kafka entrega al menos una vez y reasignar el mismo cliente al mismo
        // ejecutivo no debe sonar dos veces.
        String sourceEventId = "party:executive-assigned:" + p.partyId() + ":" + p.executiveId();
        String cliente = (p.clientName() == null || p.clientName().isBlank()) ? "un cliente" : p.clientName();

        try {
            service.notify(sourceEventId, "STAFF", p.executiveId(), EVENT_KEY,
                    Map.of("cliente", cliente), null);
        } catch (UnknownRecipientException | InvalidRecipientException ex) {
            // Se descarta en vez de reintentar: un ejecutivo que no está registrado como
            // destinatario no va a aparecer porque volvamos a intentarlo, y dejar el mensaje
            // rebotando bloquearía la partición para los avisos que sí tienen a quién llegarle.
            log.warn("Aviso de asignación descartado executiveId={}: {}", p.executiveId(), ex.getMessage());
        }
    }
}
