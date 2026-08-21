package com.fintech.party;

import com.fintech.party.application.port.out.PartyRepository;
import com.fintech.party.domain.Party;
import com.fintech.party.domain.PartyStatus;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {"origination.prospect-created"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class PartyCreationFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech")
            .withUsername("fintech")
            .withPassword("fintech");

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @TestConfiguration
    static class KafkaTestConfig {
        @Bean
        KafkaTemplate<String, String> testKafkaTemplate(
                @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
            Map<String, Object> props = Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
            );
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }
    }

    @Autowired
    PartyRepository partyRepository;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String prospectEvent(UUID prospectId, String curp) {
        return """
                {
                  "prospectId":           "%s",
                  "prospectType":         "INDIVIDUAL",
                  "firstName":            "María",
                  "lastName1":            "López",
                  "lastName2":            "Pérez",
                  "curp":                 "%s",
                  "rfc":                  "LOPM900101XXX",
                  "dateOfBirth":          "1990-01-01",
                  "circuloConsentAccepted": false,
                  "eventId":              "%s"
                }
                """.formatted(prospectId, curp, UUID.randomUUID());
    }

    private void publishProspectEvent(UUID prospectId, String curp) throws Exception {
        kafkaTemplate.send("origination.prospect-created", prospectId.toString(),
                prospectEvent(prospectId, curp)).get();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void partyCreatedAsProspect_whenNewProspectEventReceived() throws Exception {
        UUID prospectId = UUID.randomUUID();
        String curp = "LOPM900101HDFXXX01";

        publishProspectEvent(prospectId, curp);

        await().atMost(10, TimeUnit.SECONDS).until(() ->
                partyRepository.findByProspectId(prospectId).isPresent());

        Optional<Party> partyOpt = partyRepository.findByProspectId(prospectId);
        assertThat(partyOpt).isPresent();

        Party party = partyOpt.get();
        assertThat(party.getStatus()).isEqualTo(PartyStatus.PROSPECT);
        assertThat(party.getProspectId()).isEqualTo(prospectId);
        assertThat(party.getFirstName()).isEqualTo("María");
        assertThat(party.getLastName1()).isEqualTo("López");
        assertThat(party.getCurp()).isEqualTo(curp);

        // Scoring fields must be null — populated only after scoring completes
        assertThat(party.getEvaluationId()).isNull();
        assertThat(party.getRiskLevel()).isNull();
        assertThat(party.getTotalScore()).isNull();
    }

    @Test
    void idempotent_whenDuplicateEventReceived() throws Exception {
        UUID prospectId = UUID.randomUUID();
        String curp = "LOPM900102HDFXXX02";

        publishProspectEvent(prospectId, curp);

        await().atMost(10, TimeUnit.SECONDS).until(() ->
                partyRepository.findByProspectId(prospectId).isPresent());

        UUID firstPartyId = partyRepository.findByProspectId(prospectId).get().getPartyId();

        // Publish the same event again (simulated retry from origination)
        publishProspectEvent(prospectId, curp);

        Thread.sleep(2_000);

        Optional<Party> party = partyRepository.findByProspectId(prospectId);
        assertThat(party).isPresent();
        assertThat(party.get().getPartyId()).isEqualTo(firstPartyId);
    }

    @Test
    void partyType_isIndividual_forIndividualProspect() throws Exception {
        UUID prospectId = UUID.randomUUID();

        publishProspectEvent(prospectId, "LOPM900103HDFXXX03");

        await().atMost(10, TimeUnit.SECONDS).until(() ->
                partyRepository.findByProspectId(prospectId).isPresent());

        Party party = partyRepository.findByProspectId(prospectId).get();
        assertThat(party.getPartyType().name()).isEqualTo("INDIVIDUAL");
    }
}
