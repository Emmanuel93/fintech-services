package com.fintech.origination;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.origination.application.port.out.CreditApplicationRepository;
import com.fintech.origination.domain.*;
import com.fintech.origination.infrastructure.adapter.in.api.dto.RegisterProspectRequest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IT — Phase E+F contract signing loop:
 *   APPROVED → offer presented → offer accepted → contract generated
 *   → contract signed → CreditProductCreationRequested published on Kafka.
 *
 * Verifies the snapshot payload carries all fields required by credit-portfolio
 * to create a CreditAccount without calling back to the catalog at runtime.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {
                "origination.prospect-created",
                "origination.score-requested",
                "origination.contract-signed",
                "origination.credit-product-creation-requested",
                "scoring.scoring-completed",
                "credit-portfolio.credit-account-activated"
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class ContractToPortfolioFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech").withUsername("fintech").withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("fintech.origination.credit-product-service-url", () -> "http://localhost:9999");
    }

    @Autowired MockMvc mockMvc;
    @Autowired CreditApplicationRepository applicationRepository;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private KafkaMessageListenerContainer<String, Map> listenerContainer;
    private final BlockingQueue<ConsumerRecord<String, Map>> received = new LinkedBlockingQueue<>();

    @BeforeEach
    void setupConsumer(@Autowired org.springframework.kafka.test.EmbeddedKafkaBroker broker) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-contract-flow-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class,
                JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*",
                JsonDeserializer.VALUE_DEFAULT_TYPE, Map.class.getName(),
                JsonDeserializer.USE_TYPE_INFO_HEADERS, "false"
        );
        ContainerProperties containerProps = new ContainerProperties("origination.credit-product-creation-requested");
        containerProps.setMessageListener((MessageListener<String, Map>) received::add);
        listenerContainer = new KafkaMessageListenerContainer<>(
                new DefaultKafkaConsumerFactory<>(config), containerProps);
        listenerContainer.start();
        ContainerTestUtils.waitForAssignment(listenerContainer, 1);
    }

    @AfterEach
    void tearDown() {
        if (listenerContainer != null) listenerContainer.stop();
        received.clear();
    }

    // ── IT: full E+F flow using an already-APPROVED application ──────────────

    @Test
    void signContract_publishesCreditProductCreationRequested_withCompleteSnapshot() throws Exception {
        // 1. Onboard a prospect (public)
        UUID prospectId = onboardProspect("RATC850320HDFMRL10", "+5215510000010");

        // 2. Persist an APPROVED application directly (simulates scoring decision)
        CreditApplication app = CreditApplication.start(
                prospectId, ProspectType.INDIVIDUAL, ProductType.PERSONAL_LOAN,
                new BigDecimal("50000"), 12);
        app.approve("BAJO", "AUTO_APPROVED");
        applicationRepository.save(app);
        UUID applicationId = app.getApplicationId();

        // 3. Present offer (mocked catalog injected via NoopClabeValidator + real service)
        //    We call the OfferService directly since we don't have a real catalog running.
        //    Instead, embed an offer manually and call the contract flow via controllers.
        app.presentOffer(CreditOffer.create(
                "PL-IND-STD-V1", 1, "INSTALLMENT", "FRENCH",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("0.32"), new BigDecimal("0.55"),
                new BigDecimal("0.01"), new BigDecimal("34.50"),
                java.time.Instant.now().plus(72, java.time.temporal.ChronoUnit.HOURS)));
        app.acceptOffer();
        applicationRepository.save(app);

        // 4. Generate contract
        mockMvc.perform(post("/api/v1/origination/applications/{id}/contract/generate", applicationId)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-Roles", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("signatureMethod", "ELECTRONIC"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_SIGNATURE"));

        // 5. Sign contract — triggers CreditProductCreationRequested
        mockMvc.perform(post("/api/v1/origination/applications/{id}/contract/sign", applicationId)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-Roles", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "clabeAccount", "032180000118359719",
                                "signatureProof", "SIG-PROOF-OK"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTRACT_SIGNED"));

        // 6. Verify Kafka event published with complete snapshot
        ConsumerRecord<String, Map> record = received.poll(10, TimeUnit.SECONDS);
        assertThat(record).as("CreditProductCreationRequested must be published").isNotNull();

        Map<?, ?> payload = record.value();
        assertThat(payload.get("applicationId")).isNotNull();
        assertThat(payload.get("productCode")).isEqualTo("PL-IND-STD-V1");
        assertThat(payload.get("productType")).isEqualTo("PERSONAL_LOAN");
        assertThat(payload.get("productBehavior")).isEqualTo("INSTALLMENT");
        assertThat(payload.get("clabeAccount")).isEqualTo("032180000118359719");
        assertThat(payload.get("obligorPartyId")).isNotNull();
        assertThat(payload.get("contractNumber")).asString().startsWith("CTR-");
        assertThat(payload.get("nominalRate")).isNotNull();
        assertThat(payload.get("moratoriumRate")).isNotNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID onboardProspect(String curp, String phone) throws Exception {
        String suffix = phone.substring(phone.length() - 4);
        RegisterProspectRequest req = new RegisterProspectRequest(
                ProspectType.INDIVIDUAL,
                "Maria", "Lopez", "Garcia",
                curp, null,
                LocalDate.of(1990, 5, 15),
                Gender.FEMALE, "Ciudad de México",
                phone, "maria" + suffix + "@example.com",
                "Av. Reforma", "500", null,
                "Polanco", null, "CDMX", "CDMX", "11560", "MX",
                ChannelType.MOBILE_APP, true, true, List.of(),
                "maria" + suffix, "Secure1!");

        String json = mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(mapper.readTree(json).get("prospectId").asText());
    }
}
