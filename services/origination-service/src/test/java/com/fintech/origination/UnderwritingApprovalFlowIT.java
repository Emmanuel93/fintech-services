package com.fintech.origination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.origination.application.port.out.CreditApplicationRepository;
import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.ChannelType;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.Gender;
import com.fintech.origination.domain.ProductType;
import com.fintech.origination.domain.ProspectType;
import com.fintech.origination.infrastructure.adapter.in.api.dto.RegisterProspectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance — Phase H (manual/committee decisions), Phase J (UW-06 cooldown).
 * Also verifies thread-safety of the start() flow under concurrent HTTP requests.
 *
 *   AC-7   UNDER_MANUAL_REVIEW → POST /underwriting/decision approved=true  → APPROVED
 *   AC-8   UNDER_MANUAL_REVIEW → POST /underwriting/decision approved=false → REJECTED + rejectedAt set
 *   AC-9   COMMITTEE_REVIEW    → POST /underwriting/decision approved=true  → APPROVED
 *   AC-10  Cooldown: prospect rechazado no puede re-solicitar mismo producto dentro de 90 días (UW-06)
 *   AC-11  GET /underwriting/applications?prospectId filtra solo los pendientes de decisión humana
 *   AC-12  Concurrencia: 8 hilos simultáneos inician la misma (prospect, producto) → solo 1 triunfa
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext
class UnderwritingApprovalFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech")
            .withUsername("fintech")
            .withPassword("fintech");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9999");
    }

    @Autowired MockMvc mockMvc;
    @Autowired CreditApplicationRepository applicationRepository;

    @MockBean KafkaTemplate<String, Object> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID onboardProspect(String curp, String phone) throws Exception {
        given(kafkaTemplate.send(any(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));

        RegisterProspectRequest req = new RegisterProspectRequest(
                ProspectType.INDIVIDUAL,
                "María", "Gómez", "López",
                curp, null,
                LocalDate.of(1990, 7, 15),
                Gender.FEMALE, "Monterrey",
                phone, "mgomez@example.com",
                "Calle Hidalgo", "101", null,
                "Centro", null, "Nuevo León", "Nuevo León", "64000", "MX",
                ChannelType.WEB, true, true, List.of(),
                "mgomez" + phone.substring(phone.length() - 4), "Password1!");

        String json = mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(json).get("prospectId").asText());
    }

    /** Persists an application already in UNDER_MANUAL_REVIEW (bypasses scoring consumer). */
    private CreditApplication persistManualReviewApp(UUID prospectId) {
        CreditApplication app = CreditApplication.start(prospectId, ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("50000"), 12);
        app.sendToManualReview("MEDIO", "MANUAL_REVIEW");
        return applicationRepository.save(app);
    }

    /** Persists an application already in COMMITTEE_REVIEW. */
    private CreditApplication persistCommitteeReviewApp(UUID prospectId) {
        CreditApplication app = CreditApplication.start(prospectId, ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("700000"), 36);
        app.sendToCommitteeReview("ALTO", "MANUAL_REVIEW");
        return applicationRepository.save(app);
    }

    /** Persists an already-REJECTED application with rejectedAt in the past (for cooldown testing). */
    private void persistRejectedApp(UUID prospectId, Instant rejectedAt) {
        CreditApplication app = CreditApplication.start(prospectId, ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("50000"), 12);
        app.reject("Evaluación de riesgo crediticio — nivel ALTO", "ALTO");
        // Backdate rejectedAt to simulate a recent rejection (within cooldown window)
        CreditApplication saved = applicationRepository.save(app);
        // Directly update rejectedAt via a second save — the field was set by reject()
        // which uses Instant.now(); here we verify the cooldown logic uses the stored value.
        // (rejectedAt is already set by reject() to Instant.now(), which is within 90 days)
    }

    private String decisionBody(boolean approved, String reason) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("decidedBy", "underwriter-test");
        body.put("approved", approved);
        if (reason != null) body.put("rejectionReason", reason);
        return objectMapper.writeValueAsString(body);
    }

    // ── AC-7: manual approve ─────────────────────────────────────────────────────

    @Test
    void ac7_manualApproval_transitionsToApproved_setsFlowManual() throws Exception {
        UUID prospectId = onboardProspect("GOML900715MNLMPZ07", "+5218110000007");
        CreditApplication app = persistManualReviewApp(prospectId);

        String response = mockMvc.perform(
                        post("/api/v1/origination/underwriting/applications/{id}/decision",
                                app.getApplicationId())
                                .header("X-User-Id", UUID.randomUUID().toString())
                                .header("X-Roles", "UNDERWRITER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(decisionBody(true, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value("underwriter-test"))
                .andExpect(jsonPath("$.approvalFlow").value("MANUAL"))
                .andReturn().getResponse().getContentAsString();

        // Verify persisted in DB
        CreditApplication persisted = applicationRepository.findById(app.getApplicationId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(persisted.getDecidedBy()).isEqualTo("underwriter-test");
        assertThat(persisted.getApprovalFlow()).isEqualTo("MANUAL");
    }

    // ── AC-8: manual reject ──────────────────────────────────────────────────────

    @Test
    void ac8_manualRejection_transitionsToRejected_setsRejectedAt() throws Exception {
        UUID prospectId = onboardProspect("GOML900715MNLMPZ08", "+5218110000008");
        CreditApplication app = persistManualReviewApp(prospectId);

        mockMvc.perform(
                        post("/api/v1/origination/underwriting/applications/{id}/decision",
                                app.getApplicationId())
                                .header("X-User-Id", UUID.randomUUID().toString())
                                .header("X-Roles", "UNDERWRITER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(decisionBody(false, "Capacidad de pago insuficiente para el monto")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Capacidad de pago insuficiente para el monto"))
                .andExpect(jsonPath("$.rejectedAt").isNotEmpty());

        CreditApplication persisted = applicationRepository.findById(app.getApplicationId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(persisted.getRejectedAt()).isNotNull();
        assertThat(persisted.getDecidedBy()).isEqualTo("underwriter-test");
    }

    // ── AC-9: committee approve ──────────────────────────────────────────────────

    @Test
    void ac9_committeeApproval_transitionsToApproved_setsFlowCommittee() throws Exception {
        UUID prospectId = onboardProspect("GOML900715MNLMPZ09", "+5218110000009");
        CreditApplication app = persistCommitteeReviewApp(prospectId);

        mockMvc.perform(
                        post("/api/v1/origination/underwriting/applications/{id}/decision",
                                app.getApplicationId())
                                .header("X-User-Id", UUID.randomUUID().toString())
                                .header("X-Roles", "UNDERWRITER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(decisionBody(true, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvalFlow").value("COMMITTEE"));

        CreditApplication persisted = applicationRepository.findById(app.getApplicationId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(persisted.getApprovalFlow()).isEqualTo("COMMITTEE");
    }

    // ── AC-10: UW-06 cooldown ────────────────────────────────────────────────────

    @Test
    void ac10_cooldown_preventsReApplicationWithin90Days() throws Exception {
        UUID prospectId = onboardProspect("GOML900715MNLMPZ10", "+5218110000010");

        // Persist a rejected application (rejectedAt = now, within 90-day window)
        CreditApplication rejected = CreditApplication.start(prospectId, ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("50000"), 12);
        rejected.reject("Evaluación de riesgo crediticio — nivel ALTO", "ALTO");
        applicationRepository.save(rejected);

        // Attempt to re-apply for the same product
        String body = objectMapper.writeValueAsString(Map.of(
                "prospectId", prospectId.toString(),
                "productType", "PERSONAL_LOAN",
                "requestedAmount", 30000,
                "requestedTerm", 6));

        mockMvc.perform(post("/api/v1/origination/applications")
                        .header("X-User-Id", UUID.randomUUID().toString())
                                .header("X-Roles", "UNDERWRITER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://fintech.com/errors/ORIGINATION_COOLDOWN_ACTIVE"));
    }

    @Test
    void ac10b_cooldown_doesNotBlockDifferentProduct() throws Exception {
        UUID prospectId = onboardProspect("GOML900715MNLMPZ14", "+5218110000011");

        // Rejected for PERSONAL_LOAN
        CreditApplication rejected = CreditApplication.start(prospectId, ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("50000"), 12);
        rejected.reject("Evaluación de riesgo crediticio — nivel ALTO", "ALTO");
        applicationRepository.save(rejected);

        // Should be able to apply for a different product (REVOLVING_LINE)
        String body = objectMapper.writeValueAsString(Map.of(
                "prospectId", prospectId.toString(),
                "productType", "REVOLVING_LINE",
                "requestedAmount", 30000,
                "requestedTerm", 12));

        mockMvc.perform(post("/api/v1/origination/applications")
                        .header("X-User-Id", UUID.randomUUID().toString())
                                .header("X-Roles", "UNDERWRITER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    // ── AC-11: list pending decisions ────────────────────────────────────────────

    @Test
    void ac11_listPendingDecisions_returnsOnlyAwaitingHumanDecision() throws Exception {
        UUID prospectId = onboardProspect("GOML900715MNLMPZ11", "+5218110000012");

        CreditApplication manual = persistManualReviewApp(prospectId);

        // A second app in COMMITTEE_REVIEW for a different product
        CreditApplication committee = CreditApplication.start(prospectId, ProspectType.INDIVIDUAL,
                ProductType.REVOLVING_LINE, new BigDecimal("800000"), 24);
        committee.sendToCommitteeReview("ALTO", "MANUAL_REVIEW");
        applicationRepository.save(committee);

        // A third app in PENDING_SCORING — must NOT appear
        CreditApplication pending = CreditApplication.start(prospectId, ProspectType.INDIVIDUAL,
                ProductType.PAYROLL_LOAN, new BigDecimal("20000"), 6);
        applicationRepository.save(pending);

        mockMvc.perform(get("/api/v1/origination/underwriting/applications")
                        .header("X-User-Id", UUID.randomUUID().toString())
                                .header("X-Roles", "UNDERWRITER")
                        .param("prospectId", prospectId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[?(@.status == 'UNDER_MANUAL_REVIEW')]").isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.status == 'COMMITTEE_REVIEW')]").isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.status == 'PENDING_SCORING')]").isEmpty());
    }

    // ── AC-12: concurrent start — thread-safety ──────────────────────────────────

    @Test
    void ac12_concurrentStartSameProspectProduct_onlyOneApplicationCreated()
            throws Exception, InterruptedException {

        UUID prospectId = onboardProspect("GOML900715MNLMPZ12", "+5218110000013");

        String body = objectMapper.writeValueAsString(Map.of(
                "prospectId", prospectId.toString(),
                "productType", "PERSONAL_LOAN",
                "requestedAmount", 50000,
                "requestedTerm", 12));

        int threads = 8;
        CountDownLatch ready  = new CountDownLatch(threads);
        CountDownLatch start  = new CountDownLatch(1);
        CountDownLatch done   = new CountDownLatch(threads);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Integer> otherErrors = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        String authUserId = UUID.randomUUID().toString();
        String bodyFinal = body;

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    int status = mockMvc.perform(post("/api/v1/origination/applications")
                                    .header("X-User-Id", authUserId)
                                    .header("X-Roles", "UNDERWRITER")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(bodyFinal))
                            .andReturn().getResponse().getStatus();

                    if (status == 201) created.incrementAndGet();
                    else if (status == 409) conflicts.incrementAndGet();
                    else otherErrors.add(status);

                } catch (Exception e) {
                    otherErrors.add(-1);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();   // release all threads simultaneously
        done.await();
        executor.shutdown();

        assertThat(otherErrors).as("unexpected HTTP statuses").isEmpty();
        assertThat(created.get()).as("exactly one application created").isEqualTo(1);
        assertThat(conflicts.get()).as("remaining threads get 409").isEqualTo(threads - 1);

        // Verify only one row in DB
        long count = applicationRepository.findByProspectId(prospectId).stream()
                .filter(a -> a.getProductType() == ProductType.PERSONAL_LOAN)
                .count();
        assertThat(count).isEqualTo(1);
    }
}
