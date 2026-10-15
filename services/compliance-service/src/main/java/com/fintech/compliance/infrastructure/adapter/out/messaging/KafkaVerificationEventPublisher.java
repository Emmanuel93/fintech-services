package com.fintech.compliance.infrastructure.adapter.out.messaging;

import com.fintech.compliance.application.port.out.VerificationEventPublisher;
import com.fintech.compliance.domain.PersonVerification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publica el veredicto. Dos tópicos con papeles distintos, y por eso no son uno.
 *
 * <ul>
 *   <li>{@code compliance.verification-completed} — cambia en cada actualización. Lo consume `party`
 *       para guardar el veredicto, que es a quien consultan las compuertas.</li>
 *   <li>{@code compliance.confirmed-match} — sólo cuando una persona confirma una coincidencia.
 *       Obliga a reportar, y `audit` lo consume para escribir el `UIFReport`.</li>
 * </ul>
 *
 * <p>Separarlos evita que quien sólo necesita el veredicto tenga que filtrar, y deja el hecho
 * regulatorio con su propio tópico — que es lo que una revisión pregunta primero.
 */
@Component
public class KafkaVerificationEventPublisher implements VerificationEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaVerificationEventPublisher.class);

    static final String TOPIC_COMPLETED = "compliance.verification-completed";
    static final String TOPIC_CONFIRMED = "compliance.confirmed-match";

    private final KafkaTemplate<String, Object> kafka;

    public KafkaVerificationEventPublisher(KafkaTemplate<String, Object> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publishVerificationCompleted(PersonVerification v) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("verificationId",  v.getVerificationId().toString());
        payload.put("prospectId",      v.getProspectId().toString());
        payload.put("partyId",         v.getPartyId() == null ? null : v.getPartyId().toString());
        payload.put("identityStatus",  v.getIdentityStatus().name());
        payload.put("screeningStatus", v.getScreeningStatus().name());
        payload.put("allowsToProceed", v.allowsToProceed());
        payload.put("blockReason",     v.blockReason());
        payload.put("listVersions",    v.getScreenedListVersions());
        payload.put("occurredAt",      Instant.now().toString());

        // La llave es el prospecto: garantiza el orden por persona, que es lo que importa cuando un
        // veredicto se actualiza varias veces (screening, luego adjudicación, luego identidad).
        send(TOPIC_COMPLETED, v.getProspectId().toString(), payload);
    }

    @Override
    public void publishConfirmedMatch(PersonVerification v, String listedName, String program) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("verificationId", v.getVerificationId().toString());
        payload.put("partyId",        v.getPartyId() == null ? null : v.getPartyId().toString());
        payload.put("prospectId",     v.getProspectId().toString());
        payload.put("listedName",     listedName);
        payload.put("program",        program);
        payload.put("decidedBy",      v.getDecidedBy());
        payload.put("notes",          v.getDecisionNotes());
        payload.put("occurredAt",     Instant.now().toString());

        send(TOPIC_CONFIRMED, v.getProspectId().toString(), payload);
    }

    private void send(String topic, String key, Map<String, Object> payload) {
        try {
            kafka.send(topic, key, payload);
        } catch (Exception e) {
            // No tumbar la evaluación por no poder publicar: el veredicto ya está guardado y es la
            // fuente. Lo que se pierde es la propagación, y eso se ve — un party sin veredicto
            // bloquea, que es el lado seguro del fallo.
            log.error("No se pudo publicar en {} para {}: {}", topic, key, e.toString());
        }
    }
}
