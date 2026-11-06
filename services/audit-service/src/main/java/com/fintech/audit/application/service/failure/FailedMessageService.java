package com.fintech.audit.application.service.failure;

import com.fintech.audit.domain.failure.FailedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The failure registry: what failed, in which flow, and what was done about it.
 *
 * <p>A replay sends the message back to its original topic with its original key, payload and headers (its
 * correlation id among them) plus {@value #REPLAY_OF}, which names the failure it comes from. It is safe because
 * every consumer of the platform is idempotent by the fact's own key: a message that did get applied in the end is
 * not applied twice. Replaying or discarding is recorded with who did it and why.
 */
@Service
public class FailedMessageService {

    private static final Logger log = LoggerFactory.getLogger(FailedMessageService.class);
    public static final String REPLAY_OF = "x-replay-of";

    private final FailedMessageRepository repository;
    private final ReplayPublisher publisher;

    public FailedMessageService(FailedMessageRepository repository, ReplayPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    public void record(FailedMessage message) {
        if (repository.record(message)) {
            log.warn("Mensaje fallido registrado id={} topic={} servicio={} grupo={} correlationId={} causa={}: {}",
                    message.failedMessageId(), message.originalTopic(), message.service(), message.consumerGroup(),
                    message.correlationId(), message.exceptionClass(), message.exceptionMessage());
        }
    }

    public List<FailedMessage> search(String correlationId, String topic, String status, int page, int size) {
        return repository.search(blankToNull(correlationId), blankToNull(topic), blankToNull(status), Math.max(0, page),
                Math.min(Math.max(1, size), 200));
    }

    public FailedMessage get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NoSuchElementException("No existe el mensaje fallido " + id));
    }

    @Transactional
    public FailedMessage replay(UUID id, String by, String note) {
        FailedMessage m = get(id);
        if (FailedMessage.DISCARDED.equals(m.status())) {
            throw new IllegalStateException("El mensaje " + id + " fue descartado: no se reprocesa");
        }
        Map<String, byte[]> headers = new LinkedHashMap<>();
        m.headers().forEach((name, value) -> headers.put(name, Base64.getDecoder().decode(value)));
        headers.put(REPLAY_OF, id.toString().getBytes(StandardCharsets.UTF_8));
        publisher.republish(m.originalTopic(), m.messageKey(), m.payload(), headers);
        repository.markReplayed(id, by, note);
        log.warn("Mensaje fallido reprocesado id={} topic={} correlationId={} por={} nota={}",
                id, m.originalTopic(), m.correlationId(), by, note);
        return get(id);
    }

    @Transactional
    public FailedMessage discard(UUID id, String by, String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Descartar un mensaje fallido pide el motivo");
        }
        FailedMessage m = get(id);
        repository.markDiscarded(m.failedMessageId(), by, note.trim());
        log.warn("Mensaje fallido descartado id={} topic={} correlationId={} por={} motivo={}",
                id, m.originalTopic(), m.correlationId(), by, note);
        return get(id);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
