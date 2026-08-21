package com.fintech.origination.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.origination.application.RecordApprovalDecisionCommand;
import com.fintech.origination.application.port.in.FindCreditApplicationUseCase;
import com.fintech.origination.application.port.in.RecordApprovalDecisionUseCase;
import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.CooldownActiveException;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.CreditApplicationNotFoundException;
import com.fintech.origination.domain.ProductType;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {UnderwritingController.class, OriginationExceptionHandler.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, OriginationProperties.class})
@TestPropertySource(properties = {
        "fintech.origination.jwt-secret=dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo"
})
class UnderwritingControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean RecordApprovalDecisionUseCase decisionUseCase;
    @MockBean FindCreditApplicationUseCase findUseCase;
    @MockBean com.fintech.origination.application.port.in.RequestDocumentsUseCase requestDocumentsUseCase;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private final UUID applicationId = UUID.randomUUID();
    private final UUID prospectId    = UUID.randomUUID();

    private CreditApplication manualReviewApp() {
        CreditApplication app = CreditApplication.start(prospectId, ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("50000"), 12);
        app.sendToManualReview("MEDIO", "MANUAL_REVIEW");
        return app;
    }

    // ── POST /request-documents · /documents-received (E6) ────────────────────────

    @Test
    @WithMockUser(roles = "UNDERWRITER")
    void requestDocuments_returns200_pendingDocuments() throws Exception {
        CreditApplication pending = manualReviewApp();
        pending.requestDocuments("analyst-1", "Comprobante de ingresos",
                java.time.Instant.now().plusSeconds(1000));
        willDoNothing().given(requestDocumentsUseCase)
                .requestDocuments(any(com.fintech.origination.application.RequestDocumentsCommand.class));
        given(findUseCase.getById(applicationId)).willReturn(pending);

        mockMvc.perform(post("/api/v1/origination/underwriting/applications/{id}/request-documents", applicationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decidedBy", "analyst-1",
                                "note", "Comprobante de ingresos"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(ApplicationStatus.PENDING_DOCUMENTS.name()));
    }

    @Test
    @WithMockUser(roles = "UNDERWRITER")
    void requestDocuments_missingNote_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/origination/underwriting/applications/{id}/request-documents", applicationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("decidedBy", "analyst-1"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "UNDERWRITER")
    void requestDocuments_wrongStatus_returns422() throws Exception {
        willThrow(new IllegalStateException("not in review")).given(requestDocumentsUseCase)
                .requestDocuments(any(com.fintech.origination.application.RequestDocumentsCommand.class));

        mockMvc.perform(post("/api/v1/origination/underwriting/applications/{id}/request-documents", applicationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decidedBy", "analyst-1", "note", "docs"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @WithMockUser(roles = "UNDERWRITER")
    void documentsReceived_returns200_backToReview() throws Exception {
        willDoNothing().given(requestDocumentsUseCase).markDocumentsReceived(applicationId);
        given(findUseCase.getById(applicationId)).willReturn(manualReviewApp());

        mockMvc.perform(post("/api/v1/origination/underwriting/applications/{id}/documents-received", applicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(ApplicationStatus.UNDER_MANUAL_REVIEW.name()));
    }

    // ── POST /decision ───────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "UNDERWRITER")
    void recordDecision_approve_returns200() throws Exception {
        CreditApplication approved = manualReviewApp();
        approved.recordManualApproval("underwriter-001");

        willDoNothing().given(decisionUseCase).record(any(RecordApprovalDecisionCommand.class));
        given(findUseCase.getById(applicationId)).willReturn(approved);

        mockMvc.perform(post("/api/v1/origination/underwriting/applications/{id}/decision", applicationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decidedBy", "underwriter-001",
                                "approved", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(ApplicationStatus.APPROVED.name()));
    }

    @Test
    @WithMockUser(roles = "UNDERWRITER")
    void recordDecision_reject_missingReason_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/origination/underwriting/applications/{id}/decision", applicationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decidedBy", "underwriter-002",
                                "approved", false))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "UNDERWRITER")
    void recordDecision_reject_withReason_returns200() throws Exception {
        CreditApplication rejected = manualReviewApp();
        rejected.recordManualRejection("underwriter-002", "Capacidad de pago insuficiente");

        willDoNothing().given(decisionUseCase).record(any(RecordApprovalDecisionCommand.class));
        given(findUseCase.getById(applicationId)).willReturn(rejected);

        mockMvc.perform(post("/api/v1/origination/underwriting/applications/{id}/decision", applicationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decidedBy", "underwriter-002",
                                "approved", false,
                                "rejectionReason", "Capacidad de pago insuficiente"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(ApplicationStatus.REJECTED.name()));
    }

    @Test
    @WithMockUser(roles = "UNDERWRITER")
    void recordDecision_applicationNotFound_returns404() throws Exception {
        willThrow(new CreditApplicationNotFoundException(applicationId.toString()))
                .given(decisionUseCase).record(any());

        mockMvc.perform(post("/api/v1/origination/underwriting/applications/{id}/decision", applicationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decidedBy", "uw", "approved", true))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://fintech.com/errors/CREDIT_APPLICATION_NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "UNDERWRITER")
    void recordDecision_wrongStatus_returns422() throws Exception {
        willThrow(new IllegalStateException("Application not awaiting human decision"))
                .given(decisionUseCase).record(any());

        mockMvc.perform(post("/api/v1/origination/underwriting/applications/{id}/decision", applicationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decidedBy", "uw", "approved", true))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://fintech.com/errors/ORIGINATION_INVALID_STATE_TRANSITION"));
    }

    @Test
    void recordDecision_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/origination/underwriting/applications/{id}/decision", applicationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decidedBy", "uw", "approved", true))))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /applications ────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "UNDERWRITER")
    void listAwaitingDecision_returnsPage() throws Exception {
        CreditApplication pending = manualReviewApp(); // UNDER_MANUAL_REVIEW
        given(findUseCase.search(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(pending), PageRequest.of(0, 25), 1));

        mockMvc.perform(get("/api/v1/origination/underwriting/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value(ApplicationStatus.UNDER_MANUAL_REVIEW.name()));
    }

    @Test
    @WithMockUser(roles = "UNDERWRITER")
    void listAwaitingDecision_constrainsToReviewStatuses() throws Exception {
        given(findUseCase.search(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

        mockMvc.perform(get("/api/v1/origination/underwriting/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        // La bandeja de underwriting fija los estados en revisión, no toda la cartera.
        verify(findUseCase).search(
                eq(List.of(ApplicationStatus.UNDER_MANUAL_REVIEW, ApplicationStatus.COMMITTEE_REVIEW)),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any());
    }
}
