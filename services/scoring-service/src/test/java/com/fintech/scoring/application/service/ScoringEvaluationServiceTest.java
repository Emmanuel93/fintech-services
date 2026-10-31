package com.fintech.scoring.application.service;

import com.fintech.scoring.application.EvaluationNotReady;
import com.fintech.scoring.application.port.out.BureauPrefetchRepository;
import com.fintech.scoring.application.port.out.CirculoReportRepository;
import com.fintech.scoring.application.port.out.PrequalificationSnapshotRepository;
import com.fintech.scoring.application.port.out.ScoreEvaluationRepository;
import com.fintech.scoring.application.port.out.ScoringEventPublisher;
import com.fintech.scoring.application.port.out.ScoringPolicyRepository;
import com.fintech.scoring.domain.*;
import com.fintech.scoring.domain.event.ScoringCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ScoringEvaluationServiceTest {

    @Mock ScoringPolicyRepository            policyRepository;
    @Mock ScoreEvaluationRepository          evaluationRepository;
    @Mock CirculoReportRepository            reportRepository;
    @Mock ScoringEventPublisher              eventPublisher;
    @Mock PrequalificationSnapshotRepository snapshotRepository;

    ScoringEvaluationService service;

    final UUID prospectId = UUID.randomUUID();
    final UUID prefetchId = UUID.randomUUID();
    final UUID applicationId = UUID.randomUUID();

    @Mock BureauPrefetchRepository prefetchRepository;

    ScoringPolicy policy;

    @BeforeEach
    void setUp() {
        service = new ScoringEvaluationService(
                prefetchRepository, policyRepository, evaluationRepository, reportRepository,
                eventPublisher, snapshotRepository, 24L);
        lenient().when(evaluationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        policy = buildPolicy();
    }

    // ── evaluateForApplication (Phase D — triggered by ScoreRequested) ──────────

    @Test
    @org.junit.jupiter.api.DisplayName("Sin prefetch: dice el motivo, y se reintenta porque puede venir en camino")
    void evaluateForApplication_sinPrefetch_diceElMotivo() {
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.empty());
        given(prefetchRepository.existsActiveByProspectId(prospectId)).willReturn(false);
        given(prefetchRepository.findLatestByProspectId(prospectId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluateForApplication(
                applicationId, prospectId, "INDIVIDUAL", "PERSONAL_LOAN"))
                .isInstanceOf(EvaluationNotReady.class)
                .extracting(e -> ((EvaluationNotReady) e).reason())
                .isEqualTo(EvaluationNotReady.Reason.NO_PREFETCH);
        // La solicitud puede adelantarse al alta (tópicos distintos): se reintenta como carrera.
        assertThatThrownBy(() -> service.evaluateForApplication(
                applicationId, prospectId, "INDIVIDUAL", "PERSONAL_LOAN"))
                .isInstanceOf(com.fintech.scoring.application.BureauReportPending.class);

        then(evaluationRepository).should(never()).save(any());
        then(eventPublisher).should(never()).publishCompleted(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("🔑 Prefetch en vuelo: es una carrera, y SÍ vale la pena esperar")
    void evaluateForApplication_prefetchEnVuelo_esCarrera() {
        // La distinción que antes no existía: esto y «el prefetch falló» salían por el mismo
        // Optional.empty(). A uno se le espera; al otro, esperar sólo retrasa que alguien se entere.
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.empty());
        given(prefetchRepository.existsActiveByProspectId(prospectId)).willReturn(true);

        assertThatThrownBy(() -> service.evaluateForApplication(
                applicationId, prospectId, "INDIVIDUAL", "PERSONAL_LOAN"))
                .isInstanceOf(EvaluationNotReady.class)
                .matches(e -> ((EvaluationNotReady) e).isTransient(), "va llegando");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Prefetch FALLIDO: no va a llegar, así que reintentar la evaluación no sirve")
    void evaluateForApplication_prefetchFallido_noVaALlegar() {
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.empty());
        given(prefetchRepository.existsActiveByProspectId(prospectId)).willReturn(false);
        given(prefetchRepository.findLatestByProspectId(prospectId))
                .willReturn(Optional.of(failedPrefetch()));

        assertThatThrownBy(() -> service.evaluateForApplication(
                applicationId, prospectId, "INDIVIDUAL", "PERSONAL_LOAN"))
                .isInstanceOf(EvaluationNotReady.class)
                .matches(e -> !((EvaluationNotReady) e).isTransient(), "NO va llegando")
                .extracting(e -> ((EvaluationNotReady) e).reason())
                .isEqualTo(EvaluationNotReady.Reason.PREFETCH_FAILED);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Sin política activa: es configuración, y esperar no la trae")
    void evaluateForApplication_sinPolitica_noVaALlegar() {
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.of(reportWithFico(720)));
        given(policyRepository.findActiveBy(any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluateForApplication(
                applicationId, prospectId, "INDIVIDUAL", "PERSONAL_LOAN"))
                .isInstanceOf(EvaluationNotReady.class)
                .matches(e -> !((EvaluationNotReady) e).isTransient(), "NO va llegando")
                .extracting(e -> ((EvaluationNotReady) e).reason())
                .isEqualTo(EvaluationNotReady.Reason.NO_ACTIVE_POLICY);

        then(evaluationRepository).should(never()).save(any());
        then(eventPublisher).should(never()).publishCompleted(any());
    }

    @Test
    void evaluateForApplication_fico750_savesAutoApproved_publishesEventWithApplicationId() {
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.of(reportWithFico(750)));
        given(policyRepository.findActiveBy("PERSONAL_LOAN")).willReturn(Optional.of(policy));
        // FICO 750 → ≥750 (+200) + ≥700 (+100) + ≥650 (+50) = 350 → BAJO ≥200 → AUTO_APPROVED
        service.evaluateForApplication(applicationId, prospectId, "INDIVIDUAL", "PERSONAL_LOAN");

        ScoreEvaluation saved = captureLastSaved();
        assertThat(saved.getDecision()).isEqualTo(ScoringDecision.AUTO_APPROVED);
        assertThat(saved.getRiskLevel()).isEqualTo(RiskLevel.BAJO);
        assertThat(saved.getTotalScore()).isEqualTo(350);
        assertThat(saved.getProspectId()).isEqualTo(prospectId);
        assertThat(saved.getPolicyId()).isEqualTo(policy.getPolicyId());

        ArgumentCaptor<ScoringCompletedEvent> eventCaptor = ArgumentCaptor.forClass(ScoringCompletedEvent.class);
        then(eventPublisher).should().publishCompleted(eventCaptor.capture());
        ScoringCompletedEvent published = eventCaptor.getValue();
        assertThat(published.applicationId()).isEqualTo(applicationId);   // Phase D — carries applicationId
        assertThat(published.prospectId()).isEqualTo(prospectId);
        assertThat(published.riskLevel()).isEqualTo("BAJO");
        assertThat(published.decision()).isEqualTo("AUTO_APPROVED");
        assertThat(published.productTypeIntent()).isEqualTo("PERSONAL_LOAN");
    }

    @Test
    void evaluateForApplication_fico720_savesManualReview() {
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.of(reportWithFico(720)));
        given(policyRepository.findActiveBy("PERSONAL_LOAN")).willReturn(Optional.of(policy));
        // FICO 720 → ≥700 (+100) + ≥650 (+50) = 150 → MEDIO ≥100 → MANUAL_REVIEW
        service.evaluateForApplication(applicationId, prospectId, "INDIVIDUAL", "PERSONAL_LOAN");

        assertThat(captureLastSaved().getDecision()).isEqualTo(ScoringDecision.MANUAL_REVIEW);

        ArgumentCaptor<ScoringCompletedEvent> eventCaptor = ArgumentCaptor.forClass(ScoringCompletedEvent.class);
        then(eventPublisher).should().publishCompleted(eventCaptor.capture());
        assertThat(eventCaptor.getValue().decision()).isEqualTo("MANUAL_REVIEW");
    }

    @Test
    void evaluateForApplication_noFico_savesRejected() {
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.of(reportWithFico(null)));
        given(policyRepository.findActiveBy("PERSONAL_LOAN")).willReturn(Optional.of(policy));
        service.evaluateForApplication(applicationId, prospectId, "INDIVIDUAL", "PERSONAL_LOAN");

        assertThat(captureLastSaved().getDecision()).isEqualTo(ScoringDecision.REJECTED);
    }

    @Test
    void evaluateForApplication_disqualifyingMora_savesAltoRejected() {
        given(reportRepository.findByProspectId(prospectId))
                .willReturn(Optional.of(reportWithFicoAndDelinquency(750, BigDecimal.valueOf(120))));
        given(policyRepository.findActiveBy("PERSONAL_LOAN")).willReturn(Optional.of(policy));
        service.evaluateForApplication(applicationId, prospectId, "INDIVIDUAL", "PERSONAL_LOAN");

        ScoreEvaluation saved = captureLastSaved();
        assertThat(saved.getDecision()).isEqualTo(ScoringDecision.REJECTED);
        assertThat(saved.getRiskLevel()).isEqualTo(RiskLevel.ALTO);
        assertThat(saved.getRuleDetails()).anySatisfy(d -> assertThat(d.disqualifying()).isTrue());
    }

    // ── evaluateForProspect ───────────────────────────────────────────────────

    @Test
    void evaluateForProspect_noReport_throwsIllegalArgument() {
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluateForProspect(prospectId, "INDIVIDUAL", "PERSONAL_LOAN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(prospectId.toString());
    }

    @Test
    void evaluateForProspect_noPolicy_throwsIllegalArgument() {
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.of(reportWithFico(720)));
        given(policyRepository.findActiveBy(any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluateForProspect(prospectId, "INDIVIDUAL", "PERSONAL_LOAN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evaluateForProspect_validRequest_returnsAndSavesEvaluation() {
        CirculoReport report = reportWithFico(750);
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.of(report));
        given(policyRepository.findActiveBy("PERSONAL_LOAN")).willReturn(Optional.of(policy));

        ScoreEvaluation result = service.evaluateForProspect(prospectId, "INDIVIDUAL", "PERSONAL_LOAN");

        assertThat(result.getDecision()).isEqualTo(ScoringDecision.AUTO_APPROVED);
        then(evaluationRepository).should().save(any(ScoreEvaluation.class));
    }

    // ── findLatestEvaluation ──────────────────────────────────────────────────

    @Test
    void findLatestEvaluation_delegatesToRepository() {
        given(evaluationRepository.findLatestByProspectId(prospectId)).willReturn(Optional.empty());

        Optional<ScoreEvaluation> result = service.findLatestEvaluation(prospectId);

        assertThat(result).isEmpty();
        then(evaluationRepository).should().findLatestByProspectId(prospectId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // ── Sólo se evalúa un reporte SUCCESS ─────────────────────────────────────

    private CirculoReport reportWithStatus(CirculoReportStatus status) {
        return CirculoReport.builder(UUID.randomUUID(), prefetchId, prospectId)
                .status(status).errorCode("CDC_UNAVAILABLE").errorMessage("Read timed out").build();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Reporte ERROR: no se evalúa ni se decide; dice por qué")
    void evaluateForApplication_reporteError_noSeEvalua() {
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.of(reportWithStatus(CirculoReportStatus.ERROR)));
        given(policyRepository.findActiveBy("PERSONAL_LOAN")).willReturn(Optional.of(policy));

        assertThatThrownBy(() -> service.evaluateForApplication(applicationId, prospectId, "INDIVIDUAL", "PERSONAL_LOAN"))
                .isInstanceOf(EvaluationNotReady.class)
                .hasMessageContaining("CDC_UNAVAILABLE")
                .extracting(e -> ((EvaluationNotReady) e).reason())
                .isEqualTo(EvaluationNotReady.Reason.PREFETCH_FAILED);
        then(evaluationRepository).should(never()).save(any());
        then(eventPublisher).should(never()).publishCompleted(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("🔑 Sin historial (NO_HIT): score 0 por la política del producto; con sus bandas cae en ALTO y se rechaza")
    void evaluateForApplication_sinHistorial_scoreCeroPorPolitica() {
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.of(reportWithStatus(CirculoReportStatus.NO_HIT)));
        given(policyRepository.findActiveBy("PERSONAL_LOAN")).willReturn(Optional.of(policy));

        service.evaluateForApplication(applicationId, prospectId, "INDIVIDUAL", "PERSONAL_LOAN");

        ScoreEvaluation saved = captureLastSaved();
        assertThat(saved.getTotalScore()).isZero();
        assertThat(saved.getRiskLevel()).isEqualTo(RiskLevel.ALTO);
        assertThat(saved.getDecision()).isEqualTo(ScoringDecision.REJECTED);
        assertThat(saved.getRuleDetails()).singleElement()
                .satisfies(d -> assertThat(d.detail()).contains("Sin historial en buró").contains("score 0"));
        assertThat(captureLastEvent().requirements()).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("🔑 Producto para estudiantes: sin historial va a revisión y pide constancia de estudios")
    void evaluateForApplication_sinHistorial_productoQuePideDatos() {
        ScoringPolicy estudiantes = buildPolicy(new NoHistoryPolicy(150,
                java.util.List.of(NoHistoryRequirement.PROOF_OF_ENROLLMENT, NoHistoryRequirement.GUARANTOR)));
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.of(reportWithStatus(CirculoReportStatus.NOT_FOUND)));
        given(policyRepository.findActiveBy("PERSONAL_LOAN")).willReturn(Optional.of(estudiantes));

        service.evaluateForApplication(applicationId, prospectId, "INDIVIDUAL", "PERSONAL_LOAN");

        ScoreEvaluation saved = captureLastSaved();
        assertThat(saved.getTotalScore()).isEqualTo(150);
        assertThat(saved.getRiskLevel()).isEqualTo(RiskLevel.MEDIO);
        assertThat(saved.getDecision()).isEqualTo(ScoringDecision.MANUAL_REVIEW);
        assertThat(saved.getRuleDetails()).singleElement()
                .satisfies(d -> assertThat(d.detail()).contains("Constancia de estudios vigente"));
        assertThat(captureLastEvent().requirements())
                .extracting(ScoringCompletedEvent.Requirement::code)
                .containsExactly("PROOF_OF_ENROLLMENT", "GUARANTOR");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Sin historial nunca se aprueba en automático, aunque el score caiga en BAJO")
    void evaluateForApplication_sinHistorial_nuncaAutomatico() {
        ScoringPolicy generosa = buildPolicy(new NoHistoryPolicy(300, java.util.List.of()));
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.of(reportWithStatus(CirculoReportStatus.NO_HIT)));
        given(policyRepository.findActiveBy("PERSONAL_LOAN")).willReturn(Optional.of(generosa));

        service.evaluateForApplication(applicationId, prospectId, "INDIVIDUAL", "PERSONAL_LOAN");

        assertThat(captureLastSaved().getDecision()).isEqualTo(ScoringDecision.MANUAL_REVIEW);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("La re-evaluación manual también decide sin historial con la política")
    void evaluateForProspect_sinHistorial_usaLaPolitica() {
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.of(reportWithStatus(CirculoReportStatus.NO_HIT)));
        given(policyRepository.findActiveBy("PERSONAL_LOAN")).willReturn(Optional.of(policy));

        ScoreEvaluation e = service.evaluateForProspect(prospectId, "INDIVIDUAL", "PERSONAL_LOAN");

        assertThat(e.getTotalScore()).isZero();
        assertThat(e.getDecision()).isEqualTo(ScoringDecision.REJECTED);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("La precalificación sin historial usa la misma regla con que se va a decidir")
    void prequalify_sinHistorial_usaLaPolitica() {
        given(snapshotRepository.findByProspectId(prospectId)).willReturn(Optional.empty());
        given(snapshotRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.of(reportWithStatus(CirculoReportStatus.NO_HIT)));
        given(policyRepository.findActiveBy("PERSONAL_LOAN")).willReturn(Optional.of(policy));

        PrequalificationSnapshot snapshot = service.prequalify(prospectId, "INDIVIDUAL", java.util.List.of("PERSONAL_LOAN"));

        assertThat(snapshot.getResults()).singleElement().satisfies(item -> {
            assertThat(item.evaluated()).isTrue();
            assertThat(item.decision()).isEqualTo("REJECTED");
            assertThat(item.totalScore()).isZero();
        });
    }

    @Test
    void evaluateForProspect_reporteNoUtilizable_noSeEvalua() {
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.of(reportWithStatus(CirculoReportStatus.ERROR)));

        assertThatThrownBy(() -> service.evaluateForProspect(prospectId, "INDIVIDUAL", "PERSONAL_LOAN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ERROR");
    }

    private ScoringCompletedEvent captureLastEvent() {
        ArgumentCaptor<ScoringCompletedEvent> captor = ArgumentCaptor.forClass(ScoringCompletedEvent.class);
        then(eventPublisher).should().publishCompleted(captor.capture());
        return captor.getValue();
    }

    private ScoreEvaluation captureLastSaved() {
        ArgumentCaptor<ScoreEvaluation> captor = ArgumentCaptor.forClass(ScoreEvaluation.class);
        then(evaluationRepository).should().save(captor.capture());
        return captor.getValue();
    }

    private CirculoReport reportWithFico(Integer fico) {
        CirculoReport.Builder b = CirculoReport.builder(UUID.randomUUID(), prefetchId, prospectId)
                .status(CirculoReportStatus.SUCCESS);
        if (fico != null) b.ficoScoreValor(fico);
        return b.build();
    }

    private CirculoReport reportWithFicoAndDelinquency(int fico, BigDecimal peorAtraso) {
        CirculoReport report = CirculoReport.builder(UUID.randomUUID(), prefetchId, prospectId)
                .status(CirculoReportStatus.SUCCESS)
                .ficoScoreValor(fico)
                .build();
        report.addCredit(new CirculoCredit(
                UUID.randomUUID(), null,
                "CLAVE", "NOMBRE", "CTA001",
                "I", "R", "TC", "MX",
                null, 12, "M", BigDecimal.valueOf(500),
                LocalDate.now().minusYears(2), null, null, null,
                LocalDate.now(), null,
                BigDecimal.valueOf(50000), BigDecimal.valueOf(10000), BigDecimal.valueOf(50000),
                BigDecimal.ZERO, 0, "V",
                "VVVVVVVV", null, null,
                null, 24,
                peorAtraso, null, BigDecimal.ZERO,
                BigDecimal.valueOf(500), 0, LocalDate.now()));
        return report;
    }

    private ScoringPolicy buildPolicy() {
        return buildPolicy(NoHistoryPolicy.DEFAULT);
    }

    private ScoringPolicy buildPolicy(NoHistoryPolicy noHistory) {
        ScoringPolicy p = ScoringPolicy.create(UUID.randomUUID(), "INDIVIDUAL", "PERSONAL_LOAN",
                "Test Policy", null, 1, noHistory);
        p.addRule(ScoringRule.create(UUID.randomUUID(), p, RuleType.FICO_THRESHOLD, null,
                RuleOperator.GTE, BigDecimal.valueOf(750), 200, false, null, null));
        p.addRule(ScoringRule.create(UUID.randomUUID(), p, RuleType.FICO_THRESHOLD, null,
                RuleOperator.GTE, BigDecimal.valueOf(700), 100, false, null, null));
        p.addRule(ScoringRule.create(UUID.randomUUID(), p, RuleType.FICO_THRESHOLD, null,
                RuleOperator.GTE, BigDecimal.valueOf(650), 50, false, null, null));
        p.addRule(ScoringRule.create(UUID.randomUUID(), p, RuleType.WORST_ARREARS_DAYS, null,
                RuleOperator.GT, BigDecimal.valueOf(90), -500, true, null, null));
        p.addThreshold(RiskThreshold.create(UUID.randomUUID(), p, RiskLevel.BAJO, 200, ScoringDecision.AUTO_APPROVED));
        p.addThreshold(RiskThreshold.create(UUID.randomUUID(), p, RiskLevel.MEDIO, 100, ScoringDecision.MANUAL_REVIEW));
        p.addThreshold(RiskThreshold.create(UUID.randomUUID(), p, RiskLevel.ALTO, -9999, ScoringDecision.REJECTED));
        return p;
    }

    /** Un prefetch que ya terminó mal. Lo que importa es su estado, no sus datos. */
    private static com.fintech.scoring.domain.BureauPrefetch failedPrefetch() {
        var p = com.fintech.scoring.domain.BureauPrefetch.create(
                UUID.randomUUID(), UUID.randomUUID(), "CURP000000HDFXXX01", "consent-1",
                "INDIVIDUAL", "PERSONAL_LOAN");
        p.markFailed("Círculo no respondió");
        return p;
    }
}
