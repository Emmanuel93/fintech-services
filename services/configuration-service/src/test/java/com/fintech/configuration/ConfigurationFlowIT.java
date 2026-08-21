package com.fintech.configuration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration flow — approving a config parameter emits configuration.configuration-updated so
 * downstream services can refresh their T5 config. Verifies the full HTTP (maker → checker) →
 * messaging path against a real Postgres and a real (embedded) broker. Complements
 * ConfigurationAcceptanceTest (HTTP contract).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"configuration.configuration-updated"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class ConfigurationFlowIT {

    @Autowired TestRestTemplate restTemplate;
    @Autowired EmbeddedKafkaBroker embeddedKafka;

    @Value("${fintech.configuration.jwt-secret}")
    private String jwtSecret;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-config-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of("configuration.configuration-updated"));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    void approvingParameter_emitsConfigurationUpdatedEvent() {
        String paramKey = "flow_test_key_" + UUID.randomUUID().toString().substring(0, 8);

        var createResp = postJson("/api/v1/config",
                Map.of("paramKey", paramKey, "value", "99"), buildAuthHeader());
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String paramId = createResp.getBody().get("id").toString();

        var approveResp = putJson("/api/v1/config/" + paramId + "/approve", buildAuthHeader());
        assertThat(approveResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        boolean found = false;
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !found) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (paramKey.equals(record.key())
                        || (record.value() != null && record.value().contains(paramKey))) {
                    found = true;
                    break;
                }
            }
        }
        assertThat(found).as("configuration.configuration-updated emitted for %s", paramKey).isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> postJson(String path, Object body, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> putJson(String path, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.PUT, new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
    }

    private HttpHeaders buildAuthHeader() {
        SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtSecret));
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of("ADMIN"))
                .signWith(key)
                .compact();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }
}
