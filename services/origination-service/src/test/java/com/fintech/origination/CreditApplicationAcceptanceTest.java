package com.fintech.origination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.origination.domain.ChannelType;
import com.fintech.origination.domain.Gender;
import com.fintech.origination.domain.ProductType;
import com.fintech.origination.domain.ProspectType;
import com.fintech.origination.domain.event.ScoreRequestedEvent;
import com.fintech.origination.infrastructure.adapter.in.api.dto.RegisterProspectRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Acceptance — credit application flow (subdominio underwriting, ADR-001).
 *
 * Cubre el flujo: onboarding de persona → selección de producto (CreditApplication)
 * → emisión de ScoreRequested. La evaluación de scoring (consumer) es Fase C, fuera de alcance.
 *
 *   AC-1  persona onboardeada + POST /applications → 201 PENDING_SCORING + ScoreRequested publicado
 *   AC-2  GET /applications/{id} → 200
 *   AC-3  GET /applications?prospectId= → 200 lista
 *   AC-4  segunda aplicación activa mismo (prospect, producto) → 409 (OA-03)
 *   AC-5  prospect inexistente → 404
 *   AC-6  sin token → 401
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CreditApplicationAcceptanceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech")
            .withUsername("fintech")
            .withPassword("fintech");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9999");
    }

    @Autowired MockMvc mockMvc;

    @MockBean KafkaTemplate<String, Object> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String testUserId() {
        return UUID.randomUUID().toString();
    }

    /** Onboards a prospect (public endpoint) and returns its prospectId. */
    private UUID onboardProspect(String curp, String phone) throws Exception {
        given(kafkaTemplate.send(any(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));

        RegisterProspectRequest req = new RegisterProspectRequest(
                ProspectType.INDIVIDUAL,
                "Carlos", "Ramírez", "Torres",
                curp, null,
                LocalDate.of(1985, 3, 20),
                Gender.MALE, "Ciudad de México",
                phone, "carlos@example.com",
                "Av. Insurgentes Sur", "1602", null,
                "Crédito Constructor", null, "CDMX", "CDMX", "03940", "MX",
                ChannelType.MOBILE_APP, true, true, List.of(),
                "carlos" + phone.substring(phone.length() - 4), "Password1!");

        String json = mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(json);
        return UUID.fromString(node.get("prospectId").asText());
    }

    private String applicationBody(UUID prospectId, String productType) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "prospectId", prospectId.toString(),
                "productType", productType,
                "requestedAmount", 50000,
                "requestedTerm", 12));
    }

    // ── AC-1 ──────────────────────────────────────────────────────────────────

    @Test
    void ac1_startApplication_returns201PendingScoring_andPublishesScoreRequested() throws Exception {
        UUID prospectId = onboardProspect("RATC850320HDFMRL01", "+5215510000001");

        mockMvc.perform(post("/api/v1/origination/applications")
                        .header("X-User-Id", testUserId()).header("X-Roles", "CUSTOMER")
                        .header("X-Correlation-Id", "app-acc-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationBody(prospectId, "PERSONAL_LOAN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applicationId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING_SCORING"))
                .andExpect(jsonPath("$.productType").value("PERSONAL_LOAN"))
                .andExpect(jsonPath("$.prospectId").value(prospectId.toString()));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeastOnce())
                .send(eq("origination.score-requested"), any(String.class), captor.capture());

        ScoreRequestedEvent event = (ScoreRequestedEvent) captor.getValue();
        assertThat(event.getProspectId()).isEqualTo(prospectId);
        assertThat(event.getProductType()).isEqualTo(ProductType.PERSONAL_LOAN);
        assertThat(event.getProspectType()).isEqualTo(ProspectType.INDIVIDUAL);
        assertThat(event.getApplicationId()).isNotNull();
    }

    // ── AC-2 / AC-3 ─────────────────────────────────────────────────────────────

    @Test
    void ac2_getApplicationById_returns200() throws Exception {
        UUID prospectId = onboardProspect("RATC850320HDFMRL02", "+5215510000002");

        String created = mockMvc.perform(post("/api/v1/origination/applications")
                        .header("X-User-Id", testUserId()).header("X-Roles", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationBody(prospectId, "PERSONAL_LOAN")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String applicationId = objectMapper.readTree(created).get("applicationId").asText();

        mockMvc.perform(get("/api/v1/origination/applications/{id}", applicationId)
                        .header("X-User-Id", testUserId()).header("X-Roles", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(applicationId))
                .andExpect(jsonPath("$.status").value("PENDING_SCORING"));
    }

    @Test
    void ac3_listApplicationsByProspect_returns200() throws Exception {
        UUID prospectId = onboardProspect("RATC850320HDFMRL03", "+5215510000003");

        mockMvc.perform(post("/api/v1/origination/applications")
                        .header("X-User-Id", testUserId()).header("X-Roles", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationBody(prospectId, "REVOLVING_LINE")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/origination/applications")
                        .header("X-User-Id", testUserId()).header("X-Roles", "CUSTOMER")
                        .param("prospectId", prospectId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].prospectId").value(prospectId.toString()))
                .andExpect(jsonPath("$.content[0].productType").value("REVOLVING_LINE"));
    }

    // ── AC-4: OA-03 ─────────────────────────────────────────────────────────────

    @Test
    void ac4_duplicateActiveApplicationSameProduct_returns409() throws Exception {
        UUID prospectId = onboardProspect("RATC850320HDFMRL04", "+5215510000004");

        mockMvc.perform(post("/api/v1/origination/applications")
                        .header("X-User-Id", testUserId()).header("X-Roles", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationBody(prospectId, "PERSONAL_LOAN")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/origination/applications")
                        .header("X-User-Id", testUserId()).header("X-Roles", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationBody(prospectId, "PERSONAL_LOAN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://fintech.com/errors/DUPLICATE_ACTIVE_APPLICATION"));
    }

    // ── AC-5 ──────────────────────────────────────────────────────────────────

    @Test
    void ac5_applicationForUnknownProspect_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/origination/applications")
                        .header("X-User-Id", testUserId()).header("X-Roles", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationBody(UUID.randomUUID(), "PERSONAL_LOAN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://fintech.com/errors/PROSPECT_NOT_FOUND"));
    }

    // ── AC-6 ──────────────────────────────────────────────────────────────────

    @Test
    void ac6_startApplicationWithoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/origination/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationBody(UUID.randomUUID(), "PERSONAL_LOAN")))
                .andExpect(status().isUnauthorized());
    }
}
