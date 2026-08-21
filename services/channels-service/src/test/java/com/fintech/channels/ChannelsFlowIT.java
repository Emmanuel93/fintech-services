package com.fintech.channels;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration flow — channels is REST-in + Kafka-out. Starting a session over HTTP must emit a
 * channels.session-started event carrying the sessionId. Verifies the full HTTP → service →
 * messaging-adapter path against a real Postgres and a real (embedded) broker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {
        "channels.session-started", "channels.session-expired",
        "channels.intent-captured", "channels.intent-routed", "channels.intent-abandoned",
        "channels.application-started", "channels.lead-created", "channels.lead-converted"
})
@DirtiesContext
class ChannelsFlowIT {

    @Autowired TestRestTemplate restTemplate;
    @Autowired EmbeddedKafkaBroker embeddedKafka;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-channels-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of("channels.session-started"));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    void startingSession_emitsSessionStartedEvent() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-User-Id", UUID.randomUUID().toString());
        h.set("X-Roles", "CUSTOMER");

        var resp = restTemplate.exchange("/api/v1/sessions",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(Map.of("channelType", "MOBILE_APP"), h), String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        boolean found = false;
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !found) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (record.value() != null && record.value().contains("sessionId")) {
                    found = true;
                    break;
                }
            }
        }
        assertThat(found).as("channels.session-started event was published").isTrue();
    }
}
