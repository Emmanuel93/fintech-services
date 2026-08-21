package com.fintech.collections.application.service;

import com.fintech.collections.application.port.out.ContactAttemptRepository;
import com.fintech.collections.domain.ContactAttempt;
import com.fintech.collections.domain.ContactChannel;
import com.fintech.collections.domain.ContactResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Apunta en el expediente los mensajes que notifications confirmó.
 *
 * <p>Es la mitad que faltaba del registro único: cobranza pide el envío y notifications lo hace,
 * pero la prueba tiene que quedar en el caso, que es donde se audita y donde el gestor mira antes
 * de llamar. Sin esto, un agente abre un expediente en blanco sin saber que el sistema ya escribió
 * tres veces esta semana.
 *
 * <p><b>Se registra el desenlace, no la intención.</b> El apunte se escribe al confirmarse la
 * entrega o el fallo, no al pedir el envío: anotar la petición haría que la bitácora afirmara que
 * se contactó a alguien cuyo teléfono estaba dado de baja.
 */
@Service
@Transactional
public class ContactLoggingService {

    private static final Logger log = LoggerFactory.getLogger(ContactLoggingService.class);

    /** El prefijo con que cobranza marca sus peticiones: {@code dunning:{caseId}:{paso}}. */
    private static final String DUNNING_PREFIX = "dunning:";

    private final ContactAttemptRepository attemptRepository;

    public ContactLoggingService(ContactAttemptRepository attemptRepository) {
        this.attemptRepository = attemptRepository;
    }

    /**
     * Registra el desenlace de un mensaje que salió de la cadencia.
     *
     * <p>Ignora en silencio lo que no venga de cobranza: notifications manda avisos de bienvenida,
     * de desembolso y de liquidación, y ninguno es gestión de cobranza. Filtrar por el
     * {@code sourceEventId} evita que el expediente se llene de mensajes que nadie mandó para cobrar.
     */
    public void onNotificationSettled(String sourceEventId, UUID notificationId, String channel,
                                       boolean delivered, Instant occurredAt) {
        Correlacion c = parse(sourceEventId);
        if (c == null) return;

        ContactChannel canal = mapChannel(channel);
        if (canal == null) {
            log.warn("Canal desconocido '{}' en notificación {} — no se registra en el caso", channel, notificationId);
            return;
        }

        ContactAttempt attempt = ContactAttempt.automatic(
                c.caseId(), canal,
                delivered ? ContactResult.DELIVERED : ContactResult.FAILED,
                notificationId, c.step(), occurredAt);
        attemptRepository.save(attempt);
        log.info("Contacto automático registrado caseId={} canal={} resultado={}",
                c.caseId(), canal, attempt.getResult());
    }

    /** {@code dunning:{caseId}:{paso}} → caso y escalón. Cualquier otra forma se ignora. */
    private static Correlacion parse(String sourceEventId) {
        if (sourceEventId == null || !sourceEventId.startsWith(DUNNING_PREFIX)) return null;
        String[] partes = sourceEventId.split(":");
        if (partes.length < 3) return null;
        try {
            return new Correlacion(UUID.fromString(partes[1]), Integer.parseInt(partes[2]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Traduce el canal de notifications al catálogo de cobranza.
     *
     * <p>Los dos catálogos no coinciden y no tienen por qué: notifications distingue
     * {@code PUSH_NOTIFICATION} de {@code IN_APP} porque son entregas distintas, y para la gestión
     * de cobranza ambas son «push». Un canal que no mapea no se fuerza a nada — se registra el aviso
     * y no se apunta, porque inventar el canal en la bitácora es peor que no tenerlo.
     */
    private static ContactChannel mapChannel(String channel) {
        if (channel == null) return null;
        return switch (channel.toUpperCase(Locale.ROOT)) {
            case "PUSH_NOTIFICATION", "IN_APP" -> ContactChannel.PUSH;
            case "EMAIL"                       -> ContactChannel.EMAIL;
            case "WHATSAPP"                    -> ContactChannel.WHATSAPP;
            case "SMS"                         -> ContactChannel.SMS;
            case "IVR_CALLBACK"                -> ContactChannel.IVR;
            default -> null;
        };
    }

    private record Correlacion(UUID caseId, Integer step) {}
}
