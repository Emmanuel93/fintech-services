package com.fintech.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.audit.application.port.out.AuditEntryRepository;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration flow — audit is the global subscriber; any domain event it listens to must land
 * as an append-only AuditEntry. Verifies the origination.prospect-created → AuditEntry path with
 * a real Postgres and a real (embedded) broker. Audit consumes raw JSON strings, so the test
 * publishes with a StringSerializer producer.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "origination.prospect-created", "origination.score-requested",
        "origination.contract-signed", "origination.application-rejected",
        "scoring.scoring-approved", "scoring.scoring-completed",
        "configuration.configuration-updated",
        "product-catalog.product-activated", "product-catalog.product-retired",
        "charges.charge-applied"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class AuditFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech")
            .withUsername("fintech")
            .withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired AuditEntryRepository auditEntryRepository;
    @Autowired EmbeddedKafkaBroker embeddedKafka;
    @Autowired ObjectMapper objectMapper;

    private KafkaTemplate<String, String> stringTemplate() {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.producerProps(embeddedKafka));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @Test
    void flow_prospectCreated_isAuditedAppendOnly() throws Exception {
        UUID prospectId = UUID.randomUUID();
        String json = objectMapper.writeValueAsString(Map.of(
                "prospectId", prospectId.toString(),
                "prospectType", "INDIVIDUAL",
                "eventId", UUID.randomUUID().toString(),
                "occurredAt", Instant.now().toString()));

        stringTemplate().send("origination.prospect-created", prospectId.toString(), json);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var entries = auditEntryRepository.findByAggregateId(prospectId.toString(), 200);
            assertThat(entries).isNotEmpty();
            assertThat(entries).anySatisfy(e ->
                    assertThat(e.getEventType()).isEqualTo("ORIGINATION_PROSPECT_CREATED"));
        });
    }
}
