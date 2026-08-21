package com.fintech.scoring.application.service;

import com.fintech.scoring.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ScoringRuleEvaluatorTest {

    ScoringRuleEvaluator evaluator;
    ScoringPolicy        policy;

    @BeforeEach
    void setUp() {
        evaluator = new ScoringRuleEvaluator();
        policy = ScoringPolicy.create(UUID.randomUUID(), "INDIVIDUAL", "PERSONAL_LOAN",
                "Test Policy", null);
        addThreshold(RiskLevel.BAJO,  200, ScoringDecision.AUTO_APPROVED);
        addThreshold(RiskLevel.MEDIO, 100, ScoringDecision.MANUAL_REVIEW);
        addThreshold(RiskLevel.ALTO, -9999, ScoringDecision.REJECTED);
    }

    // ── FICO_THRESHOLD ────────────────────────────────────────────────────────

    @Test
    void fico_aboveThreshold_addsPositiveScore() {
        addRule(RuleType.FICO_THRESHOLD, null, RuleOperator.GTE, 700, 150, false, null);
        CirculoReport report = reportWithFico(720);

        ScoringRuleEvaluator.Result result = evaluator.evaluate(policy, report);

        assertThat(result.totalScore()).isEqualTo(150);
        assertThat(result.disqualified()).isFalse();
        assertThat(result.details()).hasSize(1);
        assertThat(result.details().get(0).matched()).isTrue();
        assertThat(result.details().get(0).scoreApplied()).isEqualTo(150);
    }

    @Test
    void fico_belowThreshold_doesNotAddScore() {
        addRule(RuleType.FICO_THRESHOLD, null, RuleOperator.GTE, 750, 200, false, null);
        CirculoReport report = reportWithFico(720);

        ScoringRuleEvaluator.Result result = evaluator.evaluate(policy, report);

        assertThat(result.totalScore()).isZero();
        assertThat(result.details().get(0).matched()).isFalse();
    }

    @Test
    void fico_multipleBands_onlyMatchingBandsAddScore() {
        // Three FICO bands: 750→+200, 700→+100, 650→+50
        addRule(RuleType.FICO_THRESHOLD, null, RuleOperator.GTE, 750, 200, false, null);
        addRule(RuleType.FICO_THRESHOLD, null, RuleOperator.GTE, 700, 100, false, null);
        addRule(RuleType.FICO_THRESHOLD, null, RuleOperator.GTE, 650,  50, false, null);
        CirculoReport report = reportWithFico(720);

        ScoringRuleEvaluator.Result result = evaluator.evaluate(policy, report);

        // FICO=720 matches ≥700 (+100) and ≥650 (+50), but NOT ≥750
        assertThat(result.totalScore()).isEqualTo(150);
    }

    @Test
    void fico_nullFico_doesNotMatch() {
        addRule(RuleType.FICO_THRESHOLD, null, RuleOperator.GTE, 650, 50, false, null);
        CirculoReport report = reportWithFico(null);

        ScoringRuleEvaluator.Result result = evaluator.evaluate(policy, report);

        assertThat(result.totalScore()).isZero();
    }

    // ── MORA_CHECK ────────────────────────────────────────────────────────────

    @Test
    void mora_anyMora_applyNegativeScore() {
        addRule(RuleType.MORA_CHECK, null, RuleOperator.GT, 0, -200, false, null);
        CirculoReport report = reportWithCredits(List.of(
                credit("TC", BigDecimal.valueOf(30), BigDecimal.ZERO),
                credit("PP", BigDecimal.ZERO, BigDecimal.ZERO)
        ));

        ScoringRuleEvaluator.Result result = evaluator.evaluate(policy, report);

        assertThat(result.totalScore()).isEqualTo(-200);
        assertThat(result.disqualified()).isFalse();
    }

    @Test
    void mora_noMora_doesNotApply() {
        addRule(RuleType.MORA_CHECK, null, RuleOperator.GT, 0, -200, false, null);
        CirculoReport report = reportWithCredits(List.of(
                credit("TC", BigDecimal.ZERO, BigDecimal.ZERO)
        ));

        assertThat(evaluator.evaluate(policy, report).totalScore()).isZero();
    }

    @Test
    void mora_specificCreditType_onlyFilters() {
        addRule(RuleType.MORA_CHECK, "FM", RuleOperator.GT, 0, -200, false, null);
        CirculoReport report = reportWithCredits(List.of(
                credit("TC", BigDecimal.valueOf(60), BigDecimal.ZERO), // mora en TC — no aplica
                credit("FM", BigDecimal.ZERO, BigDecimal.ZERO)          // FM sin mora
        ));

        // FM peorAtraso = 0, TC mora no cuenta para regla FM
        assertThat(evaluator.evaluate(policy, report).totalScore()).isZero();
    }

    @Test
    void mora_disqualifying_stopsEvaluation() {
        // First rule: disqualifying mora > 90 days
        addRule(RuleType.MORA_CHECK, null, RuleOperator.GT, 90, -500, true, null);
        // Second rule: would add score, but should not be reached
        addRule(RuleType.FICO_THRESHOLD, null, RuleOperator.GTE, 650, 50, false, null);

        CirculoReport report = reportWithFicoAndCredits(720, List.of(
                credit("TC", BigDecimal.valueOf(120), BigDecimal.valueOf(5000))
        ));

        ScoringRuleEvaluator.Result result = evaluator.evaluate(policy, report);

        assertThat(result.disqualified()).isTrue();
        assertThat(result.details()).hasSize(1); // stopped after first rule
    }

    // ── CREDIT_COUNT ──────────────────────────────────────────────────────────

    @Test
    void creditCount_underLimit_addsPositiveScore() {
        addRule(RuleType.CREDIT_COUNT, "TC", RuleOperator.LTE, 3, 50, false, null);
        CirculoReport report = reportWithCredits(List.of(
                credit("TC", BigDecimal.ZERO, BigDecimal.ZERO),
                credit("TC", BigDecimal.ZERO, BigDecimal.ZERO)
        ));

        assertThat(evaluator.evaluate(policy, report).totalScore()).isEqualTo(50);
    }

    @Test
    void creditCount_overLimit_doesNotAdd() {
        addRule(RuleType.CREDIT_COUNT, "TC", RuleOperator.LTE, 3, 50, false, null);
        CirculoReport report = reportWithCredits(List.of(
                credit("TC", BigDecimal.ZERO, BigDecimal.ZERO),
                credit("TC", BigDecimal.ZERO, BigDecimal.ZERO),
                credit("TC", BigDecimal.ZERO, BigDecimal.ZERO),
                credit("TC", BigDecimal.ZERO, BigDecimal.ZERO)
        ));

        assertThat(evaluator.evaluate(policy, report).totalScore()).isZero();
    }

    // ── BALANCE_CHECK ─────────────────────────────────────────────────────────

    @Test
    void balanceCheck_underThreshold_addsScore() {
        addRule(RuleType.BALANCE_CHECK, null, RuleOperator.LTE, 500_000, 30, false, null);
        CirculoReport report = reportWithCredits(List.of(
                credit("TC", BigDecimal.ZERO, BigDecimal.valueOf(1000)),
                credit("PP", BigDecimal.ZERO, BigDecimal.valueOf(2000))
        ));

        // Sum saldoVencido = 3000 <= 500000
        assertThat(evaluator.evaluate(policy, report).totalScore()).isEqualTo(30);
    }

    // ── INQUIRY_COUNT ─────────────────────────────────────────────────────────

    @Test
    void inquiryCount_withinPeriod_addsScore() {
        addRule(RuleType.INQUIRY_COUNT, null, RuleOperator.LTE, 5, 30, false, 12);
        CirculoReport report = reportWithInquiries(List.of(
                LocalDate.now().minusMonths(3),
                LocalDate.now().minusMonths(6)
        ));

        assertThat(evaluator.evaluate(policy, report).totalScore()).isEqualTo(30);
    }

    @Test
    void inquiryCount_olderThanPeriod_notCounted() {
        addRule(RuleType.INQUIRY_COUNT, null, RuleOperator.LTE, 2, 30, false, 6);
        CirculoReport report = reportWithInquiries(List.of(
                LocalDate.now().minusMonths(3),   // within 6m
                LocalDate.now().minusMonths(9)    // outside 6m — not counted
        ));

        // count = 1 <= 2 → matches
        assertThat(evaluator.evaluate(policy, report).totalScore()).isEqualTo(30);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void addRule(RuleType type, String creditType, RuleOperator op,
                         double threshold, int score, boolean disqualifying, Integer months) {
        ScoringRule rule = ScoringRule.create(UUID.randomUUID(), policy, type, creditType, op,
                BigDecimal.valueOf(threshold), score, disqualifying, months, null);
        policy.addRule(rule);
    }

    private void addThreshold(RiskLevel level, int minScore, ScoringDecision decision) {
        policy.addThreshold(RiskThreshold.create(UUID.randomUUID(), policy, level, minScore, decision));
    }

    private CirculoReport reportWithFico(Integer fico) {
        CirculoReport.Builder b = CirculoReport.builder(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                .status(CirculoReportStatus.SUCCESS);
        if (fico != null) b.ficoScoreValor(fico);
        return b.build();
    }

    private CirculoReport reportWithCredits(List<CirculoCredit> credits) {
        CirculoReport report = CirculoReport.builder(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                .status(CirculoReportStatus.SUCCESS).build();
        credits.forEach(report::addCredit);
        return report;
    }

    private CirculoReport reportWithFicoAndCredits(int fico, List<CirculoCredit> credits) {
        CirculoReport report = CirculoReport.builder(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                .status(CirculoReportStatus.SUCCESS).ficoScoreValor(fico).build();
        credits.forEach(report::addCredit);
        return report;
    }

    private CirculoReport reportWithInquiries(List<LocalDate> dates) {
        CirculoReport report = CirculoReport.builder(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                .status(CirculoReportStatus.SUCCESS).build();
        dates.forEach(d -> report.addInquiry(new CirculoInquiry(
                UUID.randomUUID(), report, d, "BANCO TEST", "TC", "MX",
                BigDecimal.valueOf(10000), "I")));
        return report;
    }

    private CirculoCredit credit(String tipo, BigDecimal peorAtraso, BigDecimal saldoVencido) {
        return new CirculoCredit(
                UUID.randomUUID(), null,
                "CLAVE", "NOMBRE", "CTA001",
                "I", "R", tipo, "MX",
                null, 12, "M", BigDecimal.valueOf(500),
                LocalDate.now().minusYears(2), null, null, null,
                LocalDate.now(), null,
                BigDecimal.valueOf(50000), BigDecimal.valueOf(10000), BigDecimal.valueOf(50000),
                saldoVencido, 0, "V",
                "VVVVVVVV", null, null,
                null, 24,
                peorAtraso, null, saldoVencido,
                BigDecimal.valueOf(500), 0, LocalDate.now());
    }
}
