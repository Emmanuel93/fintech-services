package com.fintech.scoring.application.service;

import com.fintech.scoring.application.port.out.CirculoReportRepository;
import com.fintech.scoring.application.port.out.PrequalificationSnapshotRepository;
import com.fintech.scoring.application.port.out.ScoreEvaluationRepository;
import com.fintech.scoring.application.port.out.ScoringEventPublisher;
import com.fintech.scoring.application.port.out.ScoringPolicyRepository;
import com.fintech.scoring.domain.*;
import com.fintech.scoring.domain.event.ScoringCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ScoringEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(ScoringEvaluationService.class);

    private final ScoringPolicyRepository   policyRepository;
    private final ScoreEvaluationRepository evaluationRepository;
    private final CirculoReportRepository   reportRepository;
    private final ScoringEventPublisher     eventPublisher;
    private final PrequalificationSnapshotRepository snapshotRepository;
    private final long                      prequalificationTtlHours;
    private final ScoringRuleEvaluator      ruleEvaluator = new ScoringRuleEvaluator();

    public ScoringEvaluationService(ScoringPolicyRepository policyRepository,
                                    ScoreEvaluationRepository evaluationRepository,
                                    CirculoReportRepository reportRepository,
                                    ScoringEventPublisher eventPublisher,
                                    PrequalificationSnapshotRepository snapshotRepository,
                                    @Value("${fintech.scoring.prequalification-ttl-hours:24}") long prequalificationTtlHours) {
        this.policyRepository    = policyRepository;
        this.evaluationRepository= evaluationRepository;
        this.reportRepository    = reportRepository;
        this.eventPublisher      = eventPublisher;
        this.snapshotRepository  = snapshotRepository;
        this.prequalificationTtlHours = prequalificationTtlHours;
    }

    /**
     * Disparado por {@code origination.score-requested} cuando el cliente elige un producto.
     * Reutiliza el reporte de buró pre-cargado en onboarding (SO-02) y corre el motor de
     * decisión para {@code (prospectType, productType)}. Emite la decisión con {@code applicationId}.
     * Corre en transacción propia para aislar fallos del flujo de prefetch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ScoreEvaluation> evaluateForApplication(UUID applicationId, UUID prospectId,
                                                            String prospectType, String productType) {
        Optional<CirculoReport> reportOpt = reportRepository.findByProspectId(prospectId);
        if (reportOpt.isEmpty()) {
            // El prefetch de onboarding aún no ha completado (o no hubo consentimiento).
            // TODO: prefetch on-demand / reintento. Por ahora se omite — la aplicación queda PENDING_SCORING.
            log.warn("No prefetched CirculoReport for prospectId={} — cannot evaluate applicationId={} yet",
                    prospectId, applicationId);
            return Optional.empty();
        }

        Optional<ScoringPolicy> policyOpt = policyRepository.findActiveBy(productType);
        if (policyOpt.isEmpty()) {
            log.warn("No active scoring policy for productType={} — skipping applicationId={} "
                    + "(prospectType={}, informativo)", productType, applicationId, prospectType);
            return Optional.empty();
        }

        CirculoReport report = reportOpt.get();
        return Optional.of(evaluate(report, report.getPrefetchId(), policyOpt.get(), applicationId));
    }

    /**
     * Re-evaluación manual con tipo explícito. Llamado desde el controller (sin applicationId).
     */
    @Transactional
    public ScoreEvaluation evaluateForProspect(UUID prospectId, String prospectType,
                                               String productTypeIntent) {
        CirculoReport report = reportRepository.findByProspectId(prospectId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No CirculoReport found for prospectId=" + prospectId));

        ScoringPolicy policy = policyRepository.findActiveBy(productTypeIntent)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No active policy for " + productTypeIntent));

        return evaluate(report, report.getPrefetchId(), policy, null);
    }

    public Optional<ScoreEvaluation> findLatestEvaluation(UUID prospectId) {
        return evaluationRepository.findLatestByProspectId(prospectId);
    }

    /**
     * Precalifica un prospecto contra varios tipos de producto de una sola vez, para el home
     * "créditos disponibles". A diferencia de {@link #evaluate}, NO escribe {@code score_evaluations}
     * ni publica {@code scoring.scoring-completed} — es de solo lectura sobre el motor de reglas,
     * por eso es seguro llamarlo repetidamente (cada carga del home). El resultado se cachea en
     * {@code prequalification_snapshots} por {@code prequalificationTtlHours}; un snapshot donde
     * ningún producto llegó a evaluarse (sin CirculoReport todavía) nunca cuenta como cache-hit,
     * para que el siguiente intento recalcule en cuanto el prefetch de buró aterrice.
     */
    @Transactional
    public PrequalificationSnapshot prequalify(UUID prospectId, String prospectType, List<String> productTypes) {
        Optional<PrequalificationSnapshot> existing = snapshotRepository.findByProspectId(prospectId);
        boolean cacheUsable = existing.isPresent()
                && existing.get().isFresh(Duration.ofHours(prequalificationTtlHours))
                && existing.get().getResults().stream().anyMatch(PrequalificationItem::evaluated);
        if (cacheUsable) {
            log.debug("Prequalification cache hit prospectId={}", prospectId);
            return existing.get();
        }

        Optional<CirculoReport> reportOpt = reportRepository.findByProspectId(prospectId);
        UUID reportId;
        List<PrequalificationItem> items;
        if (reportOpt.isPresent()) {
            CirculoReport report = reportOpt.get();
            reportId = report.getReportId();
            items = productTypes.stream()
                    .map(productType -> prequalifyOne(report, prospectType, productType))
                    .toList();
        } else {
            log.info("No prefetched CirculoReport yet for prospectId={} — transient NO_BUREAU_REPORT result",
                    prospectId);
            reportId = null;
            items = productTypes.stream()
                    .map(productType -> new PrequalificationItem(
                            productType, false, "NO_BUREAU_REPORT", null, null, 0))
                    .toList();
        }

        PrequalificationSnapshot snapshot = existing
                .map(s -> { s.refresh(reportId, items); return s; })
                .orElseGet(() -> PrequalificationSnapshot.create(prospectId, prospectType, reportId, items));
        return snapshotRepository.save(snapshot);
    }

    private PrequalificationItem prequalifyOne(CirculoReport report, String prospectType, String productType) {
        Optional<ScoringPolicy> policyOpt = policyRepository.findActiveBy(productType);
        if (policyOpt.isEmpty()) {
            return new PrequalificationItem(productType, false, "NO_ACTIVE_POLICY", null, null, 0);
        }
        ScoringPolicy policy = policyOpt.get();
        ScoringRuleEvaluator.Result result = ruleEvaluator.evaluate(policy, report);
        RiskDecision rd = resolveRiskAndDecision(result, policy);
        return new PrequalificationItem(
                productType, true, null, rd.decision().name(), rd.riskLevel().name(), result.totalScore());
    }

    // ── Core evaluation ───────────────────────────────────────────────────────

    private record RiskDecision(RiskLevel riskLevel, ScoringDecision decision) {}

    private RiskDecision resolveRiskAndDecision(ScoringRuleEvaluator.Result result, ScoringPolicy policy) {
        if (result.disqualified()) {
            return new RiskDecision(RiskLevel.ALTO, ScoringDecision.REJECTED);
        }
        RiskThreshold threshold = resolveThreshold(policy.getThresholds(), result.totalScore());
        return new RiskDecision(threshold.getRiskLevel(), threshold.getDecision());
    }

    private ScoreEvaluation evaluate(CirculoReport report, UUID prefetchId, ScoringPolicy policy,
                                     UUID applicationId) {
        log.info("Evaluating scoring prospectId={} applicationId={} policy={} ({})",
                report.getProspectId(), applicationId, policy.getPolicyId(), policy.getName());

        ScoringRuleEvaluator.Result result = ruleEvaluator.evaluate(policy, report);

        if (result.disqualified()) {
            log.info("Prospect disqualified prospectId={} score={}", report.getProspectId(), result.totalScore());
        }
        RiskDecision rd = resolveRiskAndDecision(result, policy);
        RiskLevel       riskLevel = rd.riskLevel();
        ScoringDecision decision  = rd.decision();

        log.info("Scoring result prospectId={} score={} risk={} decision={}",
                report.getProspectId(), result.totalScore(), riskLevel, decision);

        ScoreEvaluation evaluation = ScoreEvaluation.create(
                UUID.randomUUID(), prefetchId, report.getReportId(),
                report.getProspectId(), policy.getPolicyId(),
                result.totalScore(), riskLevel, decision, result.details());

        ScoreEvaluation saved = evaluationRepository.save(evaluation);

        publishCompleted(saved, policy, applicationId);

        return saved;
    }

    private void publishCompleted(ScoreEvaluation evaluation, ScoringPolicy policy, UUID applicationId) {
        try {
            ScoringCompletedEvent event = ScoringCompletedEvent.from(evaluation, policy, applicationId);
            eventPublisher.publishCompleted(event);
            log.info("ScoringCompleted event queued prospectId={} evaluationId={} decision={}",
                    evaluation.getProspectId(), evaluation.getEvaluationId(), evaluation.getDecision());
        } catch (Exception ex) {
            // Event publishing failure must not roll back the persisted evaluation
            log.error("Failed to publish ScoringCompleted prospectId={} — evaluation persisted, retry manually",
                    evaluation.getProspectId(), ex);
        }
    }

    private RiskThreshold resolveThreshold(List<RiskThreshold> thresholds, int score) {
        return thresholds.stream()
                .sorted(Comparator.comparingInt(RiskThreshold::getMinScore).reversed())
                .filter(t -> score >= t.getMinScore())
                .findFirst()
                .orElseGet(() -> thresholds.stream()
                        .min(Comparator.comparingInt(RiskThreshold::getMinScore))
                        .orElseThrow(() -> new IllegalStateException("Policy has no risk thresholds")));
    }
}
