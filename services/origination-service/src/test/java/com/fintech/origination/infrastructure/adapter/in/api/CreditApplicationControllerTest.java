package com.fintech.origination.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.origination.application.StartCreditApplicationCommand;
import com.fintech.origination.application.StartCreditApplicationResult;
import com.fintech.origination.application.port.in.FindCreditApplicationUseCase;
import com.fintech.origination.application.port.in.StartCreditApplicationUseCase;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.DuplicateActiveApplicationException;
import com.fintech.origination.domain.ProductType;
import com.fintech.origination.domain.ProspectNotFoundException;
import com.fintech.origination.domain.ProspectType;
import com.fintech.origination.infrastructure.config.OriginationProperties;
import com.fintech.origination.infrastructure.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {CreditApplicationController.class, OriginationExceptionHandler.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, OriginationProperties.class})
@TestPropertySource(properties = {
        "fintech.origination.jwt-secret=dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo"
})
class CreditApplicationControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean StartCreditApplicationUseCase startUseCase;
    @MockBean FindCreditApplicationUseCase findUseCase;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private final UUID prospectId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();

    private String validBody() throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "prospectId", prospectId.toString(),
                "productType", "PERSONAL_LOAN",
                "requestedAmount", 50000,
                "requestedTerm", 12));
    }

    private CreditApplication storedApplication() {
        return CreditApplication.start(prospectId, ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("50000"), 12);
    }

    // ── A18 · el promotor sobrevive al viaje por REST ─────────────────────────
    //
    // El controlador mandaba `null` fijo en el promoterCode, con un comentario que lo daba por
    // bueno («no channel context on a direct API-initiated application»), y el DTO ni siquiera
    // tenía el campo. Sólo la ruta de Kafka —desde la intención de canal— lo llevaba.
    //
    // La consecuencia no era un error visible sino una ausencia: cualquier crédito originado por
    // esta puerta nacía sin atribución, así que el distribuidor no cobraba comisión y el crédito no
    // aparecía en el tablero comercial, que se arma cruzando el subárbol contra los créditos por
    // promotor. La pantalla salía vacía y parecía que no había distribuidores.

    @Test
    @WithMockUser
    void a18_promoterCode_llegaAlComando() throws Exception {
        given(startUseCase.start(any(StartCreditApplicationCommand.class)))
                .willReturn(new StartCreditApplicationResult.ApplicationStarted(
                        applicationId, prospectId, ProductType.DISTRIBUTOR_LINE,
                        com.fintech.origination.domain.ApplicationStatus.PENDING_SCORING, Instant.now()));
        given(findUseCase.getById(applicationId)).willReturn(storedApplication());

        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "prospectId", prospectId.toString(),
                "productType", "DISTRIBUTOR_LINE",
                "promoterCode", "DIST0007"));

        mockMvc.perform(post("/api/v1/origination/applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<StartCreditApplicationCommand> captor =
                ArgumentCaptor.forClass(StartCreditApplicationCommand.class);
        then(startUseCase).should().start(captor.capture());
        assertThat(captor.getValue().promoterCode()).isEqualTo("DIST0007");
    }

    @Test
    @WithMockUser
    void a18_sinPromoterCode_sigueSiendoCreditoDirecto() throws Exception {
        given(startUseCase.start(any(StartCreditApplicationCommand.class)))
                .willReturn(new StartCreditApplicationResult.ApplicationStarted(
                        applicationId, prospectId, ProductType.PERSONAL_LOAN,
                        com.fintech.origination.domain.ApplicationStatus.PENDING_SCORING, Instant.now()));
        given(findUseCase.getById(applicationId)).willReturn(storedApplication());

        mockMvc.perform(post("/api/v1/origination/applications")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated());

        ArgumentCaptor<StartCreditApplicationCommand> captor =
                ArgumentCaptor.forClass(StartCreditApplicationCommand.class);
        then(startUseCase).should().start(captor.capture());
        assertThat(captor.getValue().promoterCode()).isNull();
    }

    // ── POST /api/v1/origination/applications ─────────────────────────────────

    @Test
    @WithMockUser
    void start_validRequest_returns201WithPendingScoring() throws Exception {
        given(startUseCase.start(any(StartCreditApplicationCommand.class)))
                .willReturn(new StartCreditApplicationResult.ApplicationStarted(
                        applicationId, prospectId, ProductType.PERSONAL_LOAN,
                        com.fintech.origination.domain.ApplicationStatus.PENDING_SCORING, Instant.now()));
        given(findUseCase.getById(applicationId)).willReturn(storedApplication());

        mockMvc.perform(post("/api/v1/origination/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productType").value("PERSONAL_LOAN"))
                .andExpect(jsonPath("$.status").value("PENDING_SCORING"))
                .andExpect(jsonPath("$.prospectId").value(prospectId.toString()));
    }

    @Test
    void start_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/origination/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void start_missingProductType_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "prospectId", prospectId.toString()));

        mockMvc.perform(post("/api/v1/origination/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void start_prospectNotFound_returns404() throws Exception {
        given(startUseCase.start(any()))
                .willThrow(new ProspectNotFoundException(prospectId.toString()));

        mockMvc.perform(post("/api/v1/origination/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://fintech.com/errors/PROSPECT_NOT_FOUND"));
    }

    @Test
    @WithMockUser
    void start_duplicateActiveApplication_returns409() throws Exception {
        given(startUseCase.start(any()))
                .willThrow(new DuplicateActiveApplicationException(ProductType.PERSONAL_LOAN));

        mockMvc.perform(post("/api/v1/origination/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://fintech.com/errors/DUPLICATE_ACTIVE_APPLICATION"));
    }

    // ── GET ───────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser
    void getById_returns200() throws Exception {
        given(findUseCase.getById(any())).willReturn(storedApplication());

        mockMvc.perform(get("/api/v1/origination/applications/{id}", applicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productType").value("PERSONAL_LOAN"));
    }

    @Test
    @WithMockUser
    void list_returnsPagedResults() throws Exception {
        given(findUseCase.search(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(storedApplication()), PageRequest.of(0, 25), 1));

        mockMvc.perform(get("/api/v1/origination/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].productType").value("PERSONAL_LOAN"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @WithMockUser
    void list_invalidStatus_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/origination/applications").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest());
    }
}
