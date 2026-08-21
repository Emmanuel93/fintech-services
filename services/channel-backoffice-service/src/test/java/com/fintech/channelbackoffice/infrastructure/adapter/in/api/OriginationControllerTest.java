package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient.CreditAccountResponse;
import com.fintech.channelbackoffice.application.PermissionsService;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient.ApplicationDetailResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient.ApplicationResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient.ProspectDetailResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ScoringClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ScoringClient.ScoreEvaluationResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OriginationControllerTest {

    private final OriginationClient origination = mock(OriginationClient.class);
    private final ScoringClient scoring = mock(ScoringClient.class);
    private final CreditPortfolioClient portfolio = mock(CreditPortfolioClient.class);
    private final OriginationController controller =
            new OriginationController(origination, scoring, portfolio,
                    mock(PartyClient.class), new PermissionsService(mock(IdentityClient.class)));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "staff-1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void queue_returnsMappedArray() {
        when(origination.queue("UNDER_MANUAL_REVIEW", null, null, null, null, null))
                .thenReturn(List.of(app("UNDER_MANUAL_REVIEW")));

        List<Map<String, Object>> out = controller.queue("UNDER_MANUAL_REVIEW", null, null, null, null, null);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsEntry("status", "UNDER_MANUAL_REVIEW").containsKey("applicationId");
    }

    @Test
    void queue_threadsAllFiltersToClient() {
        when(origination.queue("APPROVED", "SME_LOAN", "B2B2C", "2026-01-01", "2026-02-01", "DIST-0001"))
                .thenReturn(List.of());

        controller.queue("APPROVED", "SME_LOAN", "B2B2C", "2026-01-01", "2026-02-01", "DIST-0001");

        verify(origination).queue("APPROVED", "SME_LOAN", "B2B2C", "2026-01-01", "2026-02-01", "DIST-0001");
    }

    @Test
    void detail_composesApplicationProspectEvaluationAndExistingCredits() {
        UUID applicationId = UUID.randomUUID();
        UUID prospectId = UUID.randomUUID();
        when(origination.getById(applicationId)).thenReturn(detail(applicationId, prospectId, "APPROVED"));
        when(origination.getProspect(prospectId)).thenReturn(prospect(prospectId));
        when(scoring.latestEvaluation(prospectId)).thenReturn(evaluation(prospectId));
        when(portfolio.listByParty(prospectId)).thenReturn(List.of(account(prospectId)));

        Map<String, Object> body = controller.detail(applicationId).getBody();

        assertThat(body).isNotNull();
        assertThat(cast(body.get("application"))).containsEntry("status", "APPROVED");
        assertThat(body.get("prospect")).isInstanceOf(ProspectDetailResponse.class);
        assertThat(body.get("evaluation")).isInstanceOf(ScoreEvaluationResponse.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> credits = (List<Map<String, Object>>) body.get("existingCredits");
        assertThat(credits).hasSize(1);
        // El nombre del obligado sale del capturado, sin una llamada extra a party.
        assertThat(credits.get(0)).containsEntry("obligorName", "Juan Pérez García");
    }

    @Test
    void detail_degradesWhenScoringAndPortfolioFail() {
        UUID applicationId = UUID.randomUUID();
        UUID prospectId = UUID.randomUUID();
        when(origination.getById(applicationId)).thenReturn(detail(applicationId, prospectId, "PENDING_SCORING"));
        when(origination.getProspect(prospectId)).thenReturn(prospect(prospectId));
        when(scoring.latestEvaluation(prospectId)).thenThrow(new RuntimeException("scoring down"));
        when(portfolio.listByParty(any())).thenThrow(new RuntimeException("portfolio down"));

        Map<String, Object> body = controller.detail(applicationId).getBody();

        assertThat(body).isNotNull();
        // La solicitud es el ancla: sigue estando aunque los bloques secundarios caigan.
        assertThat(cast(body.get("application"))).containsEntry("status", "PENDING_SCORING");
        assertThat(body.get("evaluation")).isNull();
        assertThat(body.get("existingCredits")).isEqualTo(List.of());
    }

    @Test
    void detail_isBoundedToOneCallPerSource_regardlessOfExistingCreditCount() {
        UUID applicationId = UUID.randomUUID();
        UUID prospectId = UUID.randomUUID();
        when(origination.getById(applicationId)).thenReturn(detail(applicationId, prospectId, "APPROVED"));
        when(origination.getProspect(prospectId)).thenReturn(prospect(prospectId));
        when(scoring.latestEvaluation(prospectId)).thenReturn(evaluation(prospectId));
        // El sujeto tiene 50 créditos: la ficha sigue siendo UNA llamada a cartera, no 50.
        List<CreditAccountResponse> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) many.add(account(prospectId));
        when(portfolio.listByParty(prospectId)).thenReturn(many);

        Map<String, Object> body = controller.detail(applicationId).getBody();

        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> credits = (List<Map<String, Object>>) body.get("existingCredits");
        assertThat(credits).hasSize(50);
        // El invariante: el nº de llamadas del BFF no depende del nº de filas.
        verify(origination, times(1)).getById(applicationId);
        verify(origination, times(1)).getProspect(prospectId);
        verify(scoring, times(1)).latestEvaluation(prospectId);
        verify(portfolio, times(1)).listByParty(prospectId);
        verifyNoMoreInteractions(portfolio);
    }

    @Test
    void detail_includesBureauReport_whenCallerIsAnalyst() {
        authenticateAs("CREDIT_ANALYST");
        UUID applicationId = UUID.randomUUID();
        UUID prospectId = UUID.randomUUID();
        when(origination.getById(applicationId)).thenReturn(detail(applicationId, prospectId, "UNDER_MANUAL_REVIEW"));
        when(origination.getProspect(prospectId)).thenReturn(prospect(prospectId));
        when(scoring.latestEvaluation(prospectId)).thenReturn(evaluation(prospectId));
        when(portfolio.listByParty(prospectId)).thenReturn(List.of());
        when(scoring.bureauReport(prospectId)).thenReturn(Map.of("status", "SUCCESS", "ficoScoreValor", 720));

        Map<String, Object> body = controller.detail(applicationId).getBody();

        assertThat(body).isNotNull();
        assertThat(cast(body.get("bureauReport"))).containsEntry("ficoScoreValor", 720);
        verify(scoring, times(1)).bureauReport(prospectId);
    }

    @Test
    void detail_omitsBureauReport_whenCallerNotAnalyst() {
        authenticateAs("EXECUTIVE"); // atiende clientes, no analiza expedientes
        UUID applicationId = UUID.randomUUID();
        UUID prospectId = UUID.randomUUID();
        when(origination.getById(applicationId)).thenReturn(detail(applicationId, prospectId, "APPROVED"));
        when(origination.getProspect(prospectId)).thenReturn(prospect(prospectId));
        when(scoring.latestEvaluation(prospectId)).thenReturn(evaluation(prospectId));
        when(portfolio.listByParty(prospectId)).thenReturn(List.of());

        Map<String, Object> body = controller.detail(applicationId).getBody();

        assertThat(body).isNotNull();
        // La clave existe (contrato estable) pero sin dato: no se pinta el panel de buró.
        assertThat(body).containsKey("bureauReport");
        assertThat(body.get("bureauReport")).isNull();
        // Y, sobre todo, ni siquiera se llamó a scoring por el buró.
        verify(scoring, never()).bureauReport(any());
    }

    @Test
    void decide_delegatesToClient() {
        UUID id = UUID.randomUUID();

        controller.decide(id, new OriginationController.DecisionRequest(false, "capacidad insuficiente"));

        verify(origination).decide(id, false, "capacidad insuficiente");
    }

    @Test
    void requestDocuments_delegatesToClient() {
        UUID id = UUID.randomUUID();

        controller.requestDocuments(id, new OriginationController.DocumentsRequest("Comprobante de ingresos"));

        verify(origination).requestDocuments(id, "Comprobante de ingresos");
    }

    @Test
    void documentsReceived_delegatesToClient() {
        UUID id = UUID.randomUUID();

        controller.documentsReceived(id);

        verify(origination).markDocumentsReceived(id);
    }

    // ── builders ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) { return (Map<String, Object>) o; }

    private static ApplicationResponse app(String status) {
        return new ApplicationResponse(
                UUID.randomUUID(), "SOL-0001", UUID.randomUUID(), "PERSONAL_LOAN", "MANUAL_REVIEW",
                new BigDecimal("50000"), 12,
                status, "MANUAL", "MEDIO", null, null,
                null, null, null, null, null, Instant.now());
    }

    private static ApplicationDetailResponse detail(UUID applicationId, UUID prospectId, String status) {
        return new ApplicationDetailResponse(
                applicationId, prospectId, "INDIVIDUAL", "PERSONAL_LOAN", status,
                new BigDecimal("50000"), 12, UUID.randomUUID(), "MEDIO", "APPROVE", null,
                "MANUAL", "analyst-1", null, "PL-STD", "INSTALLMENT",
                new BigDecimal("50000"), null, 12, new BigDecimal("0.24"), new BigDecimal("0.28"),
                null, null, null, null, null, null, null, null, Instant.now(), Instant.now());
    }

    private static ProspectDetailResponse prospect(UUID prospectId) {
        return new ProspectDetailResponse(
                prospectId.toString(), "INDIVIDUAL", "CAPTURED",
                "Juan", "Pérez", "García", "PEGJ900101HDFRRN09", "PEGJ900101AB1",
                java.time.LocalDate.of(1990, 1, 1), "MALE", "CDMX",
                "5555555555", "juan@example.com", null, "MOBILE",
                true, Instant.now(), true, Instant.now(), List.of(), Instant.now(), Instant.now());
    }

    private static ScoreEvaluationResponse evaluation(UUID prospectId) {
        return new ScoreEvaluationResponse(
                UUID.randomUUID(), prospectId, UUID.randomUUID(), UUID.randomUUID(),
                720, "MEDIO", "APPROVE", Instant.now(), List.of());
    }

    private static CreditAccountResponse account(UUID obligorId) {
        return new CreditAccountResponse(
                UUID.randomUUID(), "CT-0001", obligorId, "PL-STD", "PERSONAL_LOAN", "INSTALLMENT", "ACTIVE",
                new BigDecimal("0.24"), 12, new BigDecimal("40000"), BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, 0, "LOW", null, null, Instant.now(),
                2, 12, 3, null, null, null, null, null);
    }
}
