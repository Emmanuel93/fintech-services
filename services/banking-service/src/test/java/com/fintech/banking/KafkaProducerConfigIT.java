package com.fintech.banking;

import com.fintech.shared.testing.AbstractIntegrationTest;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El productor publica JSON.
 *
 * <p>Sin {@code value-serializer}, Kafka usaba {@code StringSerializer} y cada evento —un record—
 * reventaba al enviarse: la publicación de este servicio fallaba siempre, y las pruebas no lo veían
 * porque sustituyen el publicador. Es el mismo defecto que ya costó una corrida en compliance.
 */
@SpringBootTest
class KafkaProducerConfigIT extends AbstractIntegrationTest {

    /** No escribe datos: no trunca nada (truncar borraría lo que siembran los changesets y usan otras pruebas). */
    @Override protected List<String> schemas() { return List.of(); }

    @Autowired ProducerFactory<?, ?> producerFactory;

    @Test
    @DisplayName("los eventos salen como JSON y sin cabeceras de tipo de este servicio")
    void eventsArePublishedAsJson() {
        Map<String, Object> config = producerFactory.getConfigurationProperties();

        assertThat(config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)).isEqualTo(JsonSerializer.class);
        assertThat(String.valueOf(config.get(JsonSerializer.ADD_TYPE_INFO_HEADERS))).isEqualTo("false");
    }

    record Payload(String eventId, LocalDate businessDate, BigDecimal amount, Instant occurredAt) {}

    @Test
    @DisplayName("un record con fechas e importes ida y vuelta: el consumidor lo lee igual")
    void aRecordWithDatesRoundTrips() {
        // El formato es el de toda la plataforma (fechas como arreglo, instantes como epoch): lo que
        // importa es que un consumidor con su tipo local lo lea idéntico.
        Payload original = new Payload("e-1", LocalDate.of(2026, 10, 30),
                new BigDecimal("1234.56"), Instant.parse("2026-10-30T18:00:00Z"));
        try (var serializer = new JsonSerializer<Object>();
             var deserializer = new org.springframework.kafka.support.serializer.JsonDeserializer<>(Payload.class, false)) {
            byte[] bytes = serializer.serialize("t", original);
            assertThat(deserializer.deserialize("t", bytes)).isEqualTo(original);
        }
    }
}
