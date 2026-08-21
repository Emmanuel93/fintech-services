package com.fintech.scoring.application.service;

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

    ScoringPolicy policy;

    @BeforeEach
    void setUp() {
        service = new ScoringEvaluationService(
                policyRepository, evaluationRepository, reportRepository, eventPublisher,
                snapshotRepository, 24L);
        lenient().when(evaluationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        policy = buildPolicy();
    }

    // ── evaluateForApplication (Phase D — triggered by ScoreRequested) ──────────

    @Test
    void evaluateForApplication_noReport_skips() {
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.empty());

        var result = service.evaluateForApplication(applicationId, prospectId, "INDIVIDUAL", "PERSONAL_LOAN");

        assertThat(result).isEmpty();
        then(evaluationRepository).should(never()).save(any());
        then(eventPublisher).should(never()).publishCompleted(any());
    }

    @Test
    void evaluateForApplication_noPolicyFound_skipsWithoutSaving() {
        given(reportRepository.findByProspectId(prospectId)).willReturn(Optional.of(reportWithFico(720)));
        given(policyRepository.findActiveBy(any())).willReturn(Optional.empty());

        var result = service.evaluateForApplication(applicationId, prospectId, "INDIVIDUAL", "PERSONAL_LOAN");

        assertThat(result).isEmpty();
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
                .willReturn(Optional.of(reportWithFicoAndMora(750, BigDecimal.valueOf(120))));
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

    private CirculoReport reportWithFicoAndMora(int fico, BigDecimal peorAtraso) {
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
        ScoringPolicy p = ScoringPolicy.create(UUID.randomUUID(), "INDIVIDUAL", "PERSONAL_LOAN",
                "Test Policy", null);
        p.addRule(ScoringRule.create(UUID.randomUUID(), p, RuleType.FICO_THRESHOLD, null,
                RuleOperator.GTE, BigDecimal.valueOf(750), 200, false, null, null));
        p.addRule(ScoringRule.create(UUID.randomUUID(), p, RuleType.FICO_THRESHOLD, null,
                RuleOperator.GTE, BigDecimal.valueOf(700), 100, false, null, null));
        p.addRule(ScoringRule.create(UUID.randomUUID(), p, RuleType.FICO_THRESHOLD, null,
                RuleOperator.GTE, BigDecimal.valueOf(650), 50, false, null, null));
        p.addRule(ScoringRule.create(UUID.randomUUID(), p, RuleType.MORA_CHECK, null,
                RuleOperator.GT, BigDecimal.valueOf(90), -500, true, null, null));
        p.addThreshold(RiskThreshold.create(UUID.randomUUID(), p, RiskLevel.BAJO, 200, ScoringDecision.AUTO_APPROVED));
        p.addThreshold(RiskThreshold.create(UUID.randomUUID(), p, RiskLevel.MEDIO, 100, ScoringDecision.MANUAL_REVIEW));
        p.addThreshold(RiskThreshold.create(UUID.randomUUID(), p, RiskLevel.ALTO, -9999, ScoringDecision.REJECTED));
        return p;
    }
}
