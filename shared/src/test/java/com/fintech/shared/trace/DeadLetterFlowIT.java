package com.fintech.shared.trace;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import com.fintech.shared.trace.kafka.KafkaCorrelationAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A listener built by hand (as 23 services do) with no error handler: the platform gives it retries and the dead
 * letter, and the dead letter keeps the flow's correlation id and the service it failed in.
 */
@SpringBootTest(classes = DeadLetterFlowIT.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.application.name=servicio-de-prueba",
        "fintech.kafka.failures.retries=2",
        "fintech.kafka.failures.initial-interval-ms=50",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer"})
@EmbeddedKafka(partitions = 1, topics = {"pruebas.hecho", "pruebas.hecho.DLT"}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class DeadLetterFlowIT {

    static final AtomicInteger INTENTOS = new AtomicInteger();
    static volatile String correlacionVista;

    @Configuration
    // Only Kafka and what is under test: the full auto-configuration would also start Redisson and look for a Redis.
    @ImportAutoConfiguration({KafkaAutoConfiguration.class, KafkaCorrelationAutoConfiguration.class})
    static class App {

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> hechaAMano(KafkaProperties properties) {
            Map<String, Object> config = properties.buildConsumerProperties(null);
            config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
            return factory;
        }

        @Bean
        Fallido fallido() {
            return new Fallido();
        }
    }

    static class Fallido {
        @KafkaListener(topics = "pruebas.hecho", groupId = "grupo-de-prueba", containerFactory = "hechaAMano")
        void escucha(String payload) {
            INTENTOS.incrementAndGet();
            correlacionVista = Correlation.current();
            throw new IllegalStateException("no se pudo aplicar " + payload);
        }
    }

    @Autowired KafkaTemplate<String, String> template;
    @Autowired EmbeddedKafkaBroker broker;

    @Test
    @DisplayName("tras los reintentos el mensaje llega a su .DLT con su correlation-id, dónde falló y por qué")
    void theFailedMessageReachesItsDeadLetterWithItsFlow() throws Exception {
        MDC.put(Correlation.MDC_KEY, "flujo-del-pago-77");
        template.send(new ProducerRecord<>("pruebas.hecho", "cuenta-1", "{\"monto\":100}")).get();
        MDC.clear();

        Map<String, Object> props = KafkaTestUtils.consumerProps("lector-dlt", "false", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, "pruebas.hecho.DLT");
            ConsumerRecord<String, String> dlt = KafkaTestUtils.getSingleRecord(consumer, "pruebas.hecho.DLT", Duration.ofSeconds(30));

            assertThat(dlt.key()).isEqualTo("cuenta-1");
            assertThat(dlt.value()).isEqualTo("{\"monto\":100}");
            assertThat(header(dlt, Correlation.KAFKA_HEADER)).isEqualTo("flujo-del-pago-77");
            assertThat(header(dlt, "x-failed-service")).isEqualTo("servicio-de-prueba");
            assertThat(header(dlt, "kafka_dlt-exception-message")).contains("no se pudo aplicar");
            assertThat(header(dlt, "kafka_dlt-original-topic")).isEqualTo("pruebas.hecho");
        }
        // First attempt plus two retries, each one under the message's correlation id.
        assertThat(INTENTOS.get()).isEqualTo(3);
        assertThat(correlacionVista).isEqualTo("flujo-del-pago-77");
    }

    private static String header(ConsumerRecord<?, ?> r, String name) {
        var h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
