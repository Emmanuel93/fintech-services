package com.fintech.notifications.application.service;

import com.fintech.notifications.application.port.out.*;
import com.fintech.notifications.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orquestador central: resuelve política → canal(es) disponibles → plantilla → adaptador →
 * registra el resultado. Nunca decide el canal a mano por evento — todo pasa por
 * {@link NotificationPolicy} (T2_notifications.md §Estrategia de canal).
 */
@Service
public class NotificationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);
    private static final String PUSH_APP_TITLE = "Fintech";

    private final NotificationPolicyRepository policyRepository;
    private final NotificationTemplateRepository templateRepository;
    private final NotificationRecordRepository recordRepository;
    private final PushNotificationAdapter pushAdapter;
    private final EmailAdapter emailAdapter;
    private final WhatsAppAdapter whatsAppAdapter;
    private final NotificationEventPublisher eventPublisher;

    public NotificationDispatchService(NotificationPolicyRepository policyRepository,
                                        NotificationTemplateRepository templateRepository,
                                        NotificationRecordRepository recordRepository,
                                        PushNotificationAdapter pushAdapter,
                                        EmailAdapter emailAdapter,
                                        WhatsAppAdapter whatsAppAdapter,
                                        NotificationEventPublisher eventPublisher) {
        this.policyRepository = policyRepository;
        this.templateRepository = templateRepository;
        this.recordRepository = recordRepository;
        this.pushAdapter = pushAdapter;
        this.emailAdapter = emailAdapter;
        this.whatsAppAdapter = whatsAppAdapter;
        this.eventPublisher = eventPublisher;
    }

    public void dispatch(String sourceEventId, EventType eventType, UUID recipientId, ContactInfo contact,
                          NotificationPreference preference, Map<String, String> variables) {
        Optional<NotificationPolicy> policyOpt = policyRepository.findActiveByEventType(eventType);
        if (policyOpt.isEmpty()) {
            log.warn("No ACTIVE NotificationPolicy for eventType={} — skipping, not inventing a channel", eventType);
            return;
        }
        NotificationPolicy policy = policyOpt.get();

        String whatsappNumber = resolveWhatsappNumber(preference, contact);
        List<NotificationChannel> candidates = new ArrayList<>();
        for (NotificationChannel channel : policy.orderedChannels()) {
            if (isAvailable(channel, preference, contact, whatsappNumber)) {
                candidates.add(channel);
            }
        }

        if (candidates.isEmpty()) {
            NotificationRecord failed = NotificationRecord.failed(
                    sourceEventId, recipientId, eventType, policy.getPrimaryChannel(), "NO_CONTACT_INFO");
            recordRepository.save(failed);
            eventPublisher.publishNotificationFailed(failed);
            log.warn("NO_CONTACT_INFO eventType={} recipientId={} — no channel had reachable contact info",
                    eventType, recipientId);
            return;
        }

        // SIMULTANEOUS intenta todos los disponibles; SEQUENTIAL_FALLBACK los intenta en orden y
        // se detiene en el primer envío exitoso (el `break` de abajo) — si el adaptador del canal
        // primario falla, sí se prueba el siguiente, no solo si el canal no está disponible.
        for (NotificationChannel channel : candidates) {
            if (recordRepository.existsBySourceEventIdAndChannel(sourceEventId, channel)) {
                continue; // idempotencia — replay de Kafka no duplica el envío
            }
            boolean sent = attempt(channel, contact, whatsappNumber, preference, eventType, variables);
            NotificationRecord record = sent
                    ? NotificationRecord.sent(sourceEventId, recipientId, eventType, channel)
                    : NotificationRecord.failed(sourceEventId, recipientId, eventType, channel, "ADAPTER_FAILURE");
            recordRepository.save(record);
            if (sent) {
                eventPublisher.publishNotificationSent(record);
            } else {
                eventPublisher.publishNotificationFailed(record);
            }
            if (sent && policy.getChannelStrategy() == ChannelStrategy.SEQUENTIAL_FALLBACK) {
                break; // ya se entregó por el primer canal disponible — no se intentan los demás
            }
        }
    }

    private boolean attempt(NotificationChannel channel, ContactInfo contact, String whatsappNumber,
                             NotificationPreference preference, EventType eventType,
                             Map<String, String> variables) {
        Optional<NotificationTemplate> templateOpt =
                templateRepository.findByEventTypeAndChannelAndLocale(eventType, channel, "es-MX");
        if (templateOpt.isEmpty()) {
            log.warn("No NotificationTemplate for eventType={} channel={} locale=es-MX", eventType, channel);
            return false;
        }
        NotificationTemplate template = templateOpt.get();
        String body = template.renderBody(variables);

        return switch (channel) {
            case PUSH_NOTIFICATION -> pushAdapter.send(preference.getPushToken(), PUSH_APP_TITLE, body);
            case WHATSAPP -> whatsAppAdapter.send(whatsappNumber, body);
            case EMAIL -> emailAdapter.send(contact.email(), template.renderSubject(variables), body);
            default -> {
                log.warn("Channel {} not implemented in v1", channel);
                yield false;
            }
        };
    }

    private boolean isAvailable(NotificationChannel channel, NotificationPreference preference,
                                 ContactInfo contact, String whatsappNumber) {
        return switch (channel) {
            case PUSH_NOTIFICATION -> preference != null && hasText(preference.getPushToken());
            case WHATSAPP -> hasText(whatsappNumber);
            case EMAIL -> hasText(contact.email());
            default -> false;
        };
    }

    private String resolveWhatsappNumber(NotificationPreference preference, ContactInfo contact) {
        if (preference != null && hasText(preference.getWhatsappNumber())) {
            return preference.getWhatsappNumber();
        }
        return contact.phone();
    }

    private boolean hasText(String s) { return s != null && !s.isBlank(); }
}
