package com.fintech.audit.failure;

import com.fintech.audit.application.service.failure.FailedMessageRepository;
import com.fintech.audit.application.service.failure.FailedMessageService;
import com.fintech.audit.application.service.failure.ReplayPublisher;
import com.fintech.audit.domain.failure.FailedMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailedMessageRegistryTest {

    /** A registry in memory and a publisher that only remembers what it was asked to send. */
    static class Memoria implements FailedMessageRepository, ReplayPublisher {
        final Map<UUID, FailedMessage> filas = new HashMap<>();
        final List<Object[]> enviados = new ArrayList<>();

        public boolean record(FailedMessage m) {
            boolean nuevo = filas.values().stream().noneMatch(f -> f.dltTopic().equals(m.dltTopic())
                    && f.dltPartition() == m.dltPartition() && f.dltOffset() == m.dltOffset());
            if (nuevo) filas.put(m.failedMessageId(), m);
            return nuevo;
        }
        public Optional<FailedMessage> findById(UUID id) { return Optional.ofNullable(filas.get(id)); }
        public List<FailedMessage> search(String c, String t, String s, int p, int n) {
            return filas.values().stream().filter(f -> c == null || c.equals(f.correlationId())).toList();
        }
        public void markReplayed(UUID id, String by, String note) { filas.put(id, with(filas.get(id), FailedMessage.REPLAYED, by)); }
        public void markDiscarded(UUID id, String by, String note) { filas.put(id, with(filas.get(id), FailedMessage.DISCARDED, by)); }
        public void republish(String topic, String key, String payload, Map<String, byte[]> headers) {
            enviados.add(new Object[]{topic, key, payload, headers});
        }
        static FailedMessage with(FailedMessage m, String status, String by) {
            return new FailedMessage(m.failedMessageId(), m.dltTopic(), m.dltPartition(), m.dltOffset(), m.originalTopic(),
                    m.originalPartition(), m.originalOffset(), m.consumerGroup(), m.service(), m.correlationId(), m.messageKey(),
                    m.payload(), m.headers(), m.exceptionClass(), m.exceptionMessage(), m.failedAt(), m.receivedAt(), status,
                    m.replays() + (FailedMessage.REPLAYED.equals(status) ? 1 : 0), by, Instant.now(), null);
        }
    }

    private static ConsumerRecord<String, String> deadLetter(long offset) {
        var r = new ConsumerRecord<>("credit-portfolio.balance-updated.DLT", 0, offset, "cuenta-1", "{\"x\":1}");
        r.headers().add("x-correlation-id", "pago-42".getBytes(StandardCharsets.UTF_8));
        r.headers().add("x-failed-service", "accounting-service".getBytes(StandardCharsets.UTF_8));
        r.headers().add(KafkaHeaders.DLT_ORIGINAL_TOPIC, "credit-portfolio.balance-updated".getBytes(StandardCharsets.UTF_8));
        r.headers().add(KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(4).putInt(7).array());
        r.headers().add(KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(8).putLong(1234L).array());
        r.headers().add(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, "accounting-service".getBytes(StandardCharsets.UTF_8));
        r.headers().add(KafkaHeaders.DLT_EXCEPTION_FQCN, "java.lang.IllegalStateException".getBytes(StandardCharsets.UTF_8));
        r.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "período cerrado".getBytes(StandardCharsets.UTF_8));
        return r;
    }

    private static FailedMessage parse(ConsumerRecord<String, String> r) throws Exception {
        Class<?> listener = Class.forName("com.fintech.audit.infrastructure.adapter.in.messaging.DeadLetterListener");
        Method m = listener.getDeclaredMethod("toFailedMessage", ConsumerRecord.class);
        m.setAccessible(true);
        return (FailedMessage) m.invoke(null, r);
    }

    @Test
    @DisplayName("la carta muerta dice qué flujo rompió, dónde y por qué; los headers originales se guardan para reenviar")
    void readsWhereAndWhyItFailed() throws Exception {
        FailedMessage m = parse(deadLetter(5));

        assertThat(m.correlationId()).isEqualTo("pago-42");
        assertThat(m.originalTopic()).isEqualTo("credit-portfolio.balance-updated");
        assertThat(m.originalPartition()).isEqualTo(7);
        assertThat(m.originalOffset()).isEqualTo(1234L);
        assertThat(m.service()).isEqualTo("accounting-service");
        assertThat(m.exceptionMessage()).isEqualTo("período cerrado");
        assertThat(m.headers()).containsKey("x-correlation-id").doesNotContainKeys("x-failed-service", KafkaHeaders.DLT_ORIGINAL_TOPIC);
    }

    @Test
    @DisplayName("una carta muerta reentregada se registra una vez")
    void recordsOnce() throws Exception {
        var memoria = new Memoria();
        var servicio = new FailedMessageService(memoria, memoria);
        servicio.record(parse(deadLetter(5)));
        servicio.record(parse(deadLetter(5)));

        assertThat(memoria.filas).hasSize(1);
    }

    @Test
    @DisplayName("reprocesar la manda a su tópico original con su llave, contenido, correlation-id y de qué falla viene")
    @SuppressWarnings("unchecked")
    void replaySendsItBackAsItWas() throws Exception {
        var memoria = new Memoria();
        var servicio = new FailedMessageService(memoria, memoria);
        FailedMessage m = parse(deadLetter(9));
        servicio.record(m);

        FailedMessage despues = servicio.replay(m.failedMessageId(), "ops-1", "se reabrió el período");

        Object[] enviado = memoria.enviados.get(0);
        assertThat(enviado[0]).isEqualTo("credit-portfolio.balance-updated");
        assertThat(enviado[1]).isEqualTo("cuenta-1");
        assertThat(enviado[2]).isEqualTo("{\"x\":1}");
        Map<String, byte[]> headers = (Map<String, byte[]>) enviado[3];
        assertThat(new String(headers.get("x-correlation-id"), StandardCharsets.UTF_8)).isEqualTo("pago-42");
        assertThat(new String(headers.get(FailedMessageService.REPLAY_OF), StandardCharsets.UTF_8)).isEqualTo(m.failedMessageId().toString());
        assertThat(despues.status()).isEqualTo(FailedMessage.REPLAYED);
        assertThat(despues.resolvedBy()).isEqualTo("ops-1");
    }

    @Test
    @DisplayName("descartar pide motivo, y lo descartado ya no se reprocesa")
    void discardNeedsAReasonAndIsFinal() throws Exception {
        var memoria = new Memoria();
        var servicio = new FailedMessageService(memoria, memoria);
        FailedMessage m = parse(deadLetter(11));
        servicio.record(m);

        assertThatThrownBy(() -> servicio.discard(m.failedMessageId(), "ops-1", " ")).isInstanceOf(IllegalArgumentException.class);
        servicio.discard(m.failedMessageId(), "ops-1", "evento de prueba");
        assertThatThrownBy(() -> servicio.replay(m.failedMessageId(), "ops-1", null)).isInstanceOf(IllegalStateException.class);
        assertThat(memoria.enviados).isEmpty();
    }
}
