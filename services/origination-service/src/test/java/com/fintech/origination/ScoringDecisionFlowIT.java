package com.fintech.origination;

import com.fintech.origination.application.port.out.CreditApplicationRepository;
import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.ProductType;
import com.fintech.origination.domain.ProspectType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase C IT — a scoring decision (scoring.scoring-completed) transitions the active
 * CreditApplication, correlated by (prospectId, productType).
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {"scoring.scoring-completed", "origination.score-requested", "origination.prospect-created"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class ScoringDecisionFlowIT {

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
        registry.add("fintech.origination.jwt-secret",
                () -> "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo");
    }

    @Autowired CreditApplicationRepository applicationRepository;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;

    private UUID persistPendingApplication(UUID prospectId) {
        CreditApplication app = CreditApplication.start(
                prospectId, ProspectType.INDIVIDUAL, ProductType.PERSONAL_LOAN,
                new BigDecimal("50000"), 12);
        return applicationRepository.save(app).getApplicationId();
    }

    private void publishDecision(UUID prospectId, String decision, String risk) throws Exception {
        Map<String, Object> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("evaluationId", UUID.randomUUID().toString());
        event.put("prospectId", prospectId.toString());
        event.put("prospectType", "INDIVIDUAL");
        event.put("productTypeIntent", "PERSONAL_LOAN");
        event.put("totalScore", 430);
        event.put("riskLevel", risk);
        event.put("decision", decision);
        kafkaTemplate.send("scoring.scoring-completed", prospectId.toString(), event).get();
    }

    private ApplicationStatus statusOf(UUID applicationId) {
        return applicationRepository.findById(applicationId).map(CreditApplication::getStatus).orElse(null);
    }

    @Test
    void autoApproved_transitionsApplicationToApproved() throws Exception {
        UUID prospectId = UUID.randomUUID();
        UUID appId = persistPendingApplication(prospectId);

        publishDecision(prospectId, "AUTO_APPROVED", "BAJO");

        await().atMost(15, TimeUnit.SECONDS)
                .until(() -> statusOf(appId) == ApplicationStatus.APPROVED);

        CreditApplication app = applicationRepository.findById(appId).orElseThrow();
        assertThat(app.getRiskLevel()).isEqualTo("BAJO");
        assertThat(app.getScoreRequestId()).isNotNull();
    }

    @Test
    void manualReview_transitionsApplicationToUnderManualReview() throws Exception {
        UUID prospectId = UUID.randomUUID();
        UUID appId = persistPendingApplication(prospectId);

        publishDecision(prospectId, "MANUAL_REVIEW", "MEDIO");

        await().atMost(15, TimeUnit.SECONDS)
                .until(() -> statusOf(appId) == ApplicationStatus.UNDER_MANUAL_REVIEW);
    }

    @Test
    void rejected_transitionsApplicationToRejected_withReason() throws Exception {
        UUID prospectId = UUID.randomUUID();
        UUID appId = persistPendingApplication(prospectId);

        publishDecision(prospectId, "REJECTED", "ALTO");

        await().atMost(15, TimeUnit.SECONDS)
                .until(() -> statusOf(appId) == ApplicationStatus.REJECTED);

        CreditApplication app = applicationRepository.findById(appId).orElseThrow();
        assertThat(app.getRejectionReason()).isNotBlank();
    }
}
