package com.fintech.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.scoring.application.port.out.CirculoGateway;
import com.fintech.scoring.application.port.out.CirculoReportRepository;
import com.fintech.scoring.application.port.out.ScoreEvaluationRepository;
import com.fintech.scoring.domain.CirculoCredit;
import com.fintech.scoring.domain.CirculoReport;
import com.fintech.scoring.domain.CirculoReportStatus;
import com.fintech.scoring.domain.ScoreEvaluation;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end scoring flow (ADR-001 Phase D):
 *   1. origination.prospect-created → PREFETCH only (bureau report stored, no evaluation)
 *   2. origination.score-requested  → DECISION ENGINE (reuses report) → scoring.scoring-completed
 *      carrying the applicationId.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {"origination.prospect-created", "origination.score-requested", "scoring.scoring-completed"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class ScoringFlowIT {

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

    @MockBean CirculoGateway circuloGateway;

    @Autowired ScoreEvaluationRepository evaluationRepository;
    @Autowired CirculoReportRepository reportRepository;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired EmbeddedKafkaBroker embeddedKafka;
    @Autowired ObjectMapper objectMapper;

    private KafkaConsumer<String, String> completedConsumer;

    @BeforeEach
    void setUpConsumer() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-scoring-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        );
        completedConsumer = new KafkaConsumer<>(props);
        completedConsumer.subscribe(List.of("scoring.scoring-completed"));
    }

    @AfterEach
    void tearDownConsumer() {
        completedConsumer.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> prospectEventMap(UUID prospectId, boolean consent) {
        Map<String, Object> map = new HashMap<>();
        map.put("prospectId", prospectId.toString());
        map.put("prospectType", "INDIVIDUAL");
        map.put("channelType", "MOBILE_APP");
        map.put("firstName", "Juan");
        map.put("lastName1", "García");
        map.put("lastName2", "López");
        map.put("curp", "GARJ900101HDFXXX01");
        map.put("rfc", "GARJ900101XXX");
        map.put("dateOfBirth", "1990-01-01");
        map.put("street", "Av. Reforma");
        map.put("exteriorNumber", "1");
        map.put("interiorNumber", null);
        map.put("neighborhood", "Cuauhtémoc");
        map.put("municipality", "Cuauhtémoc");
        map.put("city", "CDMX");
        map.put("state", "CDMX");
        map.put("postalCode", "06600");
        map.put("circuloConsentAccepted", consent);
        map.put("eventId", UUID.randomUUID().toString());
        return map;
    }

    private Map<String, Object> scoreRequestedMap(UUID applicationId, UUID prospectId) {
        Map<String, Object> map = new HashMap<>();
        map.put("applicationId", applicationId.toString());
        map.put("prospectId", prospectId.toString());
        map.put("prospectType", "INDIVIDUAL");
        map.put("productType", "PERSONAL_LOAN");
        map.put("requestedAmount", 50000);
        map.put("requestedTerm", 12);
        map.put("eventId", UUID.randomUUID().toString());
        return map;
    }

    private CirculoReport buildReport(UUID reportId, UUID prefetchId, UUID prospectId,
                                      Integer ficoScore, BigDecimal peorAtraso) {
        CirculoReport report = CirculoReport.builder(reportId, prefetchId, prospectId)
                .status(CirculoReportStatus.SUCCESS)
                .ficoScoreValor(ficoScore)
                .build();

        CirculoCredit credit = new CirculoCredit(
                UUID.randomUUID(), report,
                null, null, null, null, null, "TC", null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, peorAtraso, null, null, null, null, null);
        report.addCredit(credit);
        return report;
    }

    /** Step 1: prefetch (consume prospect-created, store bureau report). */
    private void prefetch(UUID prospectId, Integer fico, BigDecimal peorAtraso) throws Exception {
        when(circuloGateway.query(any(), any(), ArgumentMatchers.eq(prospectId), any()))
                .thenAnswer(inv -> buildReport(inv.getArgument(0), inv.getArgument(1),
                        prospectId, fico, peorAtraso));

        kafkaTemplate.send("origination.prospect-created", prospectId.toString(),
                prospectEventMap(prospectId, true)).get();

        await().atMost(15, TimeUnit.SECONDS)
                .until(() -> reportRepository.findByProspectId(prospectId).isPresent());
    }

    /** Step 2: select product → evaluate. */
    private void requestScore(UUID applicationId, UUID prospectId) throws Exception {
        kafkaTemplate.send("origination.score-requested", prospectId.toString(),
                scoreRequestedMap(applicationId, prospectId)).get();
    }

    private JsonNode awaitCompletedEvent(UUID prospectId) {
        var ref = new Object() { JsonNode node = null; };
        await().atMost(15, TimeUnit.SECONDS).until(() -> {
            var records = completedConsumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> r : records) {
                try {
                    JsonNode n = objectMapper.readTree(r.value());
                    if (prospectId.toString().equals(n.path("prospectId").asText())) {
                        ref.node = n;
                        return true;
                    }
                } catch (Exception ignored) {}
            }
            return false;
        });
        return ref.node;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void autoApproved_whenFico760AndOneTC() throws Exception {
        UUID prospectId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        prefetch(prospectId, 760, BigDecimal.ZERO);

        // No evaluation yet — prefetch only
        assertThat(evaluationRepository.findLatestByProspectId(prospectId)).isEmpty();

        requestScore(applicationId, prospectId);

        // El motor ampliado suma además ingreso, antigüedad laboral y edad del perfil de prueba:
        // 630 con las bandas reescaladas (BAJO ≥400) → sigue siendo auto-aprobación.
        await().atMost(15, TimeUnit.SECONDS).until(() ->
                evaluationRepository.findLatestByProspectId(prospectId).isPresent());

        Optional<ScoreEvaluation> eval = evaluationRepository.findLatestByProspectId(prospectId);
        assertThat(eval).isPresent();
        assertThat(eval.get().getDecision().name()).isEqualTo("AUTO_APPROVED");
        assertThat(eval.get().getRiskLevel().name()).isEqualTo("BAJO");
        assertThat(eval.get().getTotalScore()).isEqualTo(630);

        JsonNode event = awaitCompletedEvent(prospectId);
        assertThat(event.path("decision").asText()).isEqualTo("AUTO_APPROVED");
        assertThat(event.path("applicationId").asText()).isEqualTo(applicationId.toString());
        assertThat(event.path("totalScore").asInt()).isEqualTo(630);
    }

    @Test
    void manualReview_whenFico650AndOneTC() throws Exception {
        UUID prospectId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        prefetch(prospectId, 650, BigDecimal.ZERO);
        requestScore(applicationId, prospectId);

        await().atMost(15, TimeUnit.SECONDS).until(() ->
                evaluationRepository.findLatestByProspectId(prospectId).isPresent());

        ScoreEvaluation eval = evaluationRepository.findLatestByProspectId(prospectId).orElseThrow();
        assertThat(eval.getDecision().name()).isEqualTo("MANUAL_REVIEW");
        assertThat(eval.getRiskLevel().name()).isEqualTo("MEDIO");
        // 330 con el motor ampliado: cae en MEDIO (≥300, <400) y por tanto va a la mesa,
        // que es donde iba antes con 130 sobre las bandas anteriores.
        assertThat(eval.getTotalScore()).isEqualTo(330);

        JsonNode event = awaitCompletedEvent(prospectId);
        assertThat(event.path("decision").asText()).isEqualTo("MANUAL_REVIEW");
        assertThat(event.path("applicationId").asText()).isEqualTo(applicationId.toString());
    }

    @Test
    void rejected_whenNoFicoScore() throws Exception {
        UUID prospectId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        prefetch(prospectId, null, BigDecimal.ZERO);
        requestScore(applicationId, prospectId);

        await().atMost(15, TimeUnit.SECONDS).until(() ->
                evaluationRepository.findLatestByProspectId(prospectId).isPresent());

        ScoreEvaluation eval = evaluationRepository.findLatestByProspectId(prospectId).orElseThrow();
        assertThat(eval.getDecision().name()).isEqualTo("REJECTED");
        assertThat(eval.getRiskLevel().name()).isEqualTo("ALTO");

        JsonNode event = awaitCompletedEvent(prospectId);
        assertThat(event.path("decision").asText()).isEqualTo("REJECTED");
    }

    @Test
    void rejected_whenMoraDisqualifies() throws Exception {
        UUID prospectId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        prefetch(prospectId, 760, new BigDecimal("120"));   // peorAtraso 120 → disqualifying
        requestScore(applicationId, prospectId);

        await().atMost(15, TimeUnit.SECONDS).until(() ->
                evaluationRepository.findLatestByProspectId(prospectId).isPresent());

        ScoreEvaluation eval = evaluationRepository.findLatestByProspectId(prospectId).orElseThrow();
        assertThat(eval.getDecision().name()).isEqualTo("REJECTED");
        assertThat(eval.getRiskLevel().name()).isEqualTo("ALTO");
    }

    @Test
    void noPrefetch_whenConsentNotAccepted() throws Exception {
        UUID prospectId = UUID.randomUUID();

        kafkaTemplate.send("origination.prospect-created", prospectId.toString(),
                prospectEventMap(prospectId, false)).get();

        Thread.sleep(3_000);

        // consent=false → no prefetch → no report and no evaluation
        assertThat(reportRepository.findByProspectId(prospectId)).isEmpty();
        assertThat(evaluationRepository.findLatestByProspectId(prospectId)).isEmpty();
    }
}
