package com.fintech.notifications.application.service;

import com.fintech.notifications.application.port.out.*;
import com.fintech.notifications.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Notificar a una entidad abstracta: el camino que no sabe a quién le habla.
 *
 * <p><b>El que envía controla qué manda y a dónde.</b> Este servicio no deriva el destinatario de
 * ningún journey: lo recibe. Quien conoce a la entidad —origination al dar de alta un cliente,
 * identity al crear un empleado, sales-org al registrar una distribuidora— la inscribe en el
 * registro; este servicio sólo resuelve dirección, aplica política y despacha.
 *
 * <p>Convive con {@link NotificationTriggerService}, que sigue atendiendo el journey de crédito por
 * eventos de Kafka. Aquél es <b>un emisor más</b> de este mismo mecanismo, no un modelo aparte: sus
 * destinatarios son de tipo {@code PARTY} y sus claves, las del catálogo interno.
 */
@Service
@Transactional
public class RecipientNotificationService {

    private static final Logger log = LoggerFactory.getLogger(RecipientNotificationService.class);

    private final NotificationRecipientRepository recipientRepository;
    private final NotificationPolicyRepository policyRepository;
    private final NotificationTemplateRepository templateRepository;
    private final NotificationRecordRepository recordRepository;
    private final PushNotificationAdapter pushAdapter;
    private final EmailAdapter emailAdapter;
    private final WhatsAppAdapter whatsAppAdapter;
    private final NotificationEventPublisher eventPublisher;

    public RecipientNotificationService(NotificationRecipientRepository recipientRepository,
                                         NotificationPolicyRepository policyRepository,
                                         NotificationTemplateRepository templateRepository,
                                         NotificationRecordRepository recordRepository,
                                         PushNotificationAdapter pushAdapter,
                                         EmailAdapter emailAdapter,
                                         WhatsAppAdapter whatsAppAdapter,
                                         NotificationEventPublisher eventPublisher) {
        this.recipientRepository = recipientRepository;
        this.policyRepository    = policyRepository;
        this.templateRepository  = templateRepository;
        this.recordRepository    = recordRepository;
        this.pushAdapter         = pushAdapter;
        this.emailAdapter        = emailAdapter;
        this.whatsAppAdapter     = whatsAppAdapter;
        this.eventPublisher      = eventPublisher;
    }

    /** Alta o actualización de un destinatario. Idempotente por (tipo, id). */
    public NotificationRecipient register(String recipientType, UUID recipientId, String displayName,
                                           String phone, String email, String pushToken, String locale) {
        return recipientRepository.find(recipientType, recipientId)
                .map(existing -> {
                    existing.update(displayName, phone, email, pushToken, locale);
                    return recipientRepository.save(existing);
                })
                .orElseGet(() -> recipientRepository.save(NotificationRecipient.of(
                        recipientType, recipientId, displayName, phone, email, pushToken, locale)));
    }

    /**
     * Envía un aviso a una entidad.
     *
     * @param channelsOverride canales que impone el emisor. Nulo o vacío deja decidir a la política.
     *                         Existe porque «a dónde» es del emisor: hay avisos que sólo tienen
     *                         sentido dentro de la consola y no deben salir por WhatsApp aunque la
     *                         entidad tenga teléfono.
     * @return los registros escritos, uno por canal intentado
     */
    public List<NotificationRecord> notify(String sourceEventId, String recipientType, UUID recipientId,
                                            String eventKey, Map<String, String> variables,
                                            List<NotificationChannel> channelsOverride) {

        NotificationRecipient recipient = recipientRepository.find(recipientType, recipientId)
                .orElseThrow(() -> new UnknownRecipientException(recipientType, recipientId));

        List<NotificationChannel> channels = resolveChannels(eventKey, channelsOverride);
        if (channels.isEmpty()) {
            log.warn("Sin política ni canales para eventKey={} — no se inventa uno", eventKey);
            return List.of();
        }

        List<NotificationChannel> reachable = channels.stream().filter(recipient::reachableBy).toList();
        if (reachable.isEmpty()) {
            NotificationRecord failed = NotificationRecord.failedTo(
                    sourceEventId, recipientType, recipientId, eventKey, channels.get(0), "NO_CONTACT_INFO");
            recordRepository.save(failed);
            eventPublisher.publishNotificationFailed(failed);
            log.warn("NO_CONTACT_INFO eventKey={} recipient={}/{}", eventKey, recipientType, recipientId);
            return List.of(failed);
        }

        List<NotificationRecord> written = new ArrayList<>();
        for (NotificationChannel channel : reachable) {
            // Idempotencia: un reenvío del mismo hecho no vuelve a molestar a nadie.
            if (recordRepository.existsBySourceEventIdAndChannel(sourceEventId, channel)) continue;

            boolean sent = deliver(channel, recipient, eventKey, variables);
            NotificationRecord record = sent
                    ? NotificationRecord.sentTo(sourceEventId, recipientType, recipientId, eventKey, channel)
                    : NotificationRecord.failedTo(sourceEventId, recipientType, recipientId, eventKey,
                            channel, "ADAPTER_FAILURE");
            recordRepository.save(record);
            written.add(record);

            if (sent) eventPublisher.publishNotificationSent(record);
            else      eventPublisher.publishNotificationFailed(record);
        }
        return written;
    }

    /**
     * Qué canales usar: los que impone el emisor, o los de la política de la clave.
     *
     * <p>Sin política y sin imposición no se manda nada. Elegir un canal por defecto sería que el
     * notificador decidiera por el emisor justo en lo que el emisor controla.
     */
    private List<NotificationChannel> resolveChannels(String eventKey,
                                                       List<NotificationChannel> override) {
        if (override != null && !override.isEmpty()) return override;
        return policyRepository.findActiveByEventKey(eventKey)
                .map(NotificationPolicy::orderedChannels)
                .orElseGet(List::of);
    }

    private boolean deliver(NotificationChannel channel, NotificationRecipient recipient,
                            String eventKey, Map<String, String> variables) {
        // IN_APP no sale a ningún proveedor: el mensaje ES el registro, y la campana lo lee de ahí.
        if (channel == NotificationChannel.IN_APP) return true;

        Optional<NotificationTemplate> template = templateRepository
                .findByEventKeyAndChannelAndLocale(eventKey, channel, recipient.getLocale());
        if (template.isEmpty()) {
            log.warn("Sin plantilla eventKey={} canal={} locale={}", eventKey, channel, recipient.getLocale());
            return false;
        }
        Map<String, String> vars = variables == null ? Map.of() : variables;
        String body    = template.get().renderBody(vars);
        String subject = template.get().renderSubject(vars);

        try {
            return switch (channel) {
                case PUSH_NOTIFICATION -> pushAdapter.send(recipient.getPushToken(),
                        subject, body);
                case EMAIL    -> emailAdapter.send(recipient.getEmail(), subject, body);
                case WHATSAPP -> whatsAppAdapter.send(recipient.getPhone(), body);
                // Sin proveedor propio todavía: se declara no entregado en vez de darlo por bueno.
                case SMS, IVR_CALLBACK -> false;
                case IN_APP -> true;
            };
        } catch (RuntimeException ex) {
            log.warn("Falló el envío por {} a {}/{}: {}", channel,
                    recipient.getRecipientType(), recipient.getRecipientId(), ex.toString());
            return false;
        }
    }
}
