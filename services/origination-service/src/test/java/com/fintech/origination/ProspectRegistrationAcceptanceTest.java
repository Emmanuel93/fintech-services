package com.fintech.origination;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.origination.domain.ChannelType;
import com.fintech.origination.domain.Gender;
import com.fintech.origination.domain.IncomeProofType;
import com.fintech.origination.domain.ProspectDocumentType;
import com.fintech.origination.domain.event.ProspectCreatedEvent;
import com.fintech.origination.infrastructure.adapter.in.api.dto.ProspectDocumentRequest;
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
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Acceptance — prospect onboarding (subdominio application-intake).
 *
 * Verifica: AC-1 happy path, AC-2/3 duplicados (CURP/teléfono), AC-4/5 validación,
 * y el CONTRATO Kafka de origination.prospect-created — que tras ADR-001 ya NO
 * lleva productTypeIntent (el producto se elige en una CreditApplication aparte).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProspectRegistrationAcceptanceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech")
            .withUsername("fintech")
            .withPassword("fintech");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9999");
        registry.add("fintech.origination.jwt-secret",
                () -> "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo");
    }

    @Autowired MockMvc mockMvc;

    @MockBean KafkaTemplate<String, Object> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private RegisterProspectRequest buildRequest(String curp, String phone) {
        return new RegisterProspectRequest(
                null,
                "Carlos", "Ramírez", "Torres",
                curp, null,
                LocalDate.of(1985, 3, 20),
                Gender.MALE, "Ciudad de México",
                phone, "carlos@example.com",
                "Av. Insurgentes Sur", "1602", null,
                "Crédito Constructor", null, "CDMX", "CDMX", "03940", "MX",
                ChannelType.MOBILE_APP,
                true, true,
                List.of(
                        new ProspectDocumentRequest(ProspectDocumentType.INE_FRONT,    "s3://bucket/ine-front.jpg",  null, null, null, null),
                        new ProspectDocumentRequest(ProspectDocumentType.INE_BACK,     "s3://bucket/ine-back.jpg",   null, null, null, null),
                        new ProspectDocumentRequest(ProspectDocumentType.ADDRESS_PROOF,"s3://bucket/address.pdf",    null, null, null, null),
                        new ProspectDocumentRequest(ProspectDocumentType.INCOME_PROOF, "s3://bucket/income.pdf",     IncomeProofType.PAYROLL, null, null, null)
                ),
                "carlosrt", "Password1!");
    }

    // ── Tests de comportamiento HTTP ──────────────────────────────────────────

    @Test
    void ac1_register_happyPath_returns201() throws Exception {
        given(kafkaTemplate.send(any(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));

        mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "acc-test-001")
                        .content(objectMapper.writeValueAsString(
                                buildRequest("RATC850320HDFMRL09", "+5215512345678"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.prospectId").isNotEmpty())
                .andExpect(jsonPath("$.curp").value("RATC850320HDFMRL09"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void ac2_register_duplicateCurp_returns409() throws Exception {
        given(kafkaTemplate.send(any(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));

        String body = objectMapper.writeValueAsString(
                buildRequest("LOAA900101MDFPNA00", "+5213312345678"));

        mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://fintech.com/errors/DUPLICATE_PROSPECT"));
    }

    @Test
    void ac3_register_duplicatePhone_returns409() throws Exception {
        given(kafkaTemplate.send(any(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));

        mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildRequest("GOMA800101HDFNZR01", "+5215599887766"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildRequest("PERA900202HDFDRB02", "+5215599887766"))))
                .andExpect(status().isConflict());
    }

    @Test
    void ac4_register_missingRequiredFields_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ac5_register_invalidPostalCode_returns400() throws Exception {
        RegisterProspectRequest bad = new RegisterProspectRequest(
                null,
                "Ana", "López", null,
                "LOAA900101MDFPNA01", null,
                LocalDate.of(1990, 1, 1),
                Gender.FEMALE, "Jalisco",
                "+5213312344321", null,
                "Calle 1", "10", null, "Col", null, "Guadalajara", "Jalisco",
                "NOTAZIP", "MX",
                ChannelType.WEB, true, true, List.of(),
                "analo", "Password1!");

        mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
    }

    // ── Contrato Kafka: payload publicado a origination.prospect-created ──────
    //
    // Verifica que el evento contiene lo que scoring/party/identity necesitan, y que
    // tras ADR-001 ya NO carga productTypeIntent (el producto se elige en CreditApplication).

    @Test
    void kafkaEvent_publishedToCorrectTopic_withFieldsRequiredByConsumers() throws Exception {
        given(kafkaTemplate.send(any(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));

        mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "contract-test-001")
                        .content(objectMapper.writeValueAsString(
                                buildRequest("RATC850320HDFMRL08", "+5215512345699"))))
                .andExpect(status().isCreated());

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(
                eq("origination.prospect-created"),
                any(String.class),
                payloadCaptor.capture());

        Object raw = payloadCaptor.getValue();
        assertThat(raw).isInstanceOf(ProspectCreatedEvent.class);
        ProspectCreatedEvent event = (ProspectCreatedEvent) raw;

        // Requerido por scoring (prefetch): identidad + dirección + consentimiento
        assertThat(event.getProspectId()).isNotNull();
        assertThat(event.getProspectType()).isNotNull();
        assertThat(event.getChannelType()).isEqualTo(ChannelType.MOBILE_APP);
        assertThat(event.getCurp()).isEqualTo("RATC850320HDFMRL08");
        assertThat(event.getDateOfBirth()).isEqualTo(LocalDate.of(1985, 3, 20));
        assertThat(event.getStreet()).isNotBlank();
        assertThat(event.getExteriorNumber()).isNotBlank();
        assertThat(event.getPostalCode()).isNotBlank();
        assertThat(event.getState()).isNotBlank();
        assertThat(event.isCirculoConsentAccepted()).isTrue();
        assertThat(event.getEventId()).isNotNull();

        // Requerido por party: nombre
        assertThat(event.getFirstName()).isEqualTo("Carlos");
        assertThat(event.getLastName1()).isEqualTo("Ramírez");
        assertThat(event.getLastName2()).isEqualTo("Torres");

        // Requerido por identity: username + password (raw; identity hace el hashing)
        assertThat(event.getUsername()).isNotBlank();
        assertThat(event.getPassword()).isNotBlank();
    }

    @Test
    void kafkaEvent_withoutConsent_circuloConsentIsFalse() throws Exception {
        RegisterProspectRequest noConsent = new RegisterProspectRequest(
                null,
                "María", "González", "Pérez",
                "GOPM900101MDFXXX01", null,
                LocalDate.of(1990, 1, 1),
                Gender.FEMALE, "CDMX",
                "+5215500000001", "maria@example.com",
                "Reforma", "1", null, "Cuauhtémoc", null, "CDMX", "CDMX", "06600", "MX",
                ChannelType.WEB,
                true,   // privacyNoticeAccepted = true
                false,  // circuloConsentAccepted = false
                List.of(
                        new ProspectDocumentRequest(ProspectDocumentType.INE_FRONT, "s3://ine.jpg", null, null, null, null)
                ),
                "mariagp", "Password1!");

        given(kafkaTemplate.send(any(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));

        mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noConsent)))
                .andExpect(status().isCreated());

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("origination.prospect-created"), any(), payloadCaptor.capture());

        ProspectCreatedEvent event = (ProspectCreatedEvent) payloadCaptor.getValue();
        // scoring-service verifica este flag antes de consultar el buró
        assertThat(event.isCirculoConsentAccepted()).isFalse();
    }
}
