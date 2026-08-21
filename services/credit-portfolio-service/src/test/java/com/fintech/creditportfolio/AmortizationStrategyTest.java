package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.service.AmortizationEngine;
import com.fintech.creditportfolio.domain.Installment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Config-driven amortisation: GERMAN, BULLET, and frequency-aware due dates. */
class AmortizationStrategyTest {

    final AmortizationEngine engine = new AmortizationEngine();
    final UUID scheduleId = UUID.randomUUID();
    final LocalDate first = LocalDate.of(2026, 7, 1);

    // ── GERMAN: fixed principal, decreasing interest ────────────────────────

    @Test
    void german_hasFixedPrincipalAndDecreasingInterest() {
        List<Installment> schedule = engine.generate(
                scheduleId, new BigDecimal("12000"), new BigDecimal("0.24"), 12,
                "GERMAN", "MONTHLY", first);

        assertThat(schedule).hasSize(12);
        // Principal fixed at 1000 for all but last (which clears residue)
        assertThat(schedule.get(0).getPrincipalAmount()).isEqualByComparingTo("1000.00");
        assertThat(schedule.get(5).getPrincipalAmount()).isEqualByComparingTo("1000.00");
        // Interest strictly decreasing
        assertThat(schedule.get(0).getInterestAmount())
                .isGreaterThan(schedule.get(1).getInterestAmount());
        assertThat(schedule.get(1).getInterestAmount())
                .isGreaterThan(schedule.get(2).getInterestAmount());
        // Total principal equals loan
        BigDecimal totalPrincipal = schedule.stream()
                .map(Installment::getPrincipalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalPrincipal).isEqualByComparingTo("12000.00");
    }

    // ── BULLET: interest only, principal at maturity ────────────────────────

    @Test
    void bullet_interestOnlyUntilMaturity() {
        List<Installment> schedule = engine.generate(
                scheduleId, new BigDecimal("100000"), new BigDecimal("18.0"), 6,
                "BULLET", "MONTHLY", first);

        assertThat(schedule).hasSize(6);
        // All but last have zero principal
        for (int i = 0; i < 5; i++) {
            assertThat(schedule.get(i).getPrincipalAmount()).isEqualByComparingTo("0.00");
            assertThat(schedule.get(i).getInterestAmount()).isPositive();
        }
        // Last carries full principal
        assertThat(schedule.get(5).getPrincipalAmount()).isEqualByComparingTo("100000");
        // Interest constant (same balance each period)
        assertThat(schedule.get(0).getInterestAmount())
                .isEqualByComparingTo(schedule.get(4).getInterestAmount());
    }

    /**
     * El interés del primer período es el que delata la escala de la tasa.
     *
     * <p>Los demás casos comprueban la <b>forma</b> —cuota constante, capital fijo, interés
     * decreciente— y esa forma se cumple igual con la tasa cien veces más chica: el plan se ve
     * perfecto y cobra $8.33 donde tocan $833.33. Sólo un importe absoluto lo detecta.
     *
     * <p>50,000 al 24% anual, primer mes: 50,000 × 0.24 / 12 = 1,000.
     */
    @Test
    void firstInstallmentInterest_matchesTheAnnualRate() {
        List<Installment> schedule = engine.generate(
                scheduleId, new BigDecimal("50000"), new BigDecimal("0.24"), 12,
                "FRENCH", "MONTHLY", first);

        assertThat(schedule.get(0).getInterestAmount()).isEqualByComparingTo("1000.00");
    }

    // ── FRENCH via generate() ───────────────────────────────────────────────

    // ── Frequency-aware due dates ───────────────────────────────────────────

    @Test
    void weekly_dueDatesStepByWeek() {
        List<Installment> schedule = engine.generate(
                scheduleId, new BigDecimal("5000"), new BigDecimal("52.0"), 4,
                "FRENCH", "WEEKLY", first);
        assertThat(schedule.get(0).getDueDate()).isEqualTo(first);
        assertThat(schedule.get(1).getDueDate()).isEqualTo(first.plusWeeks(1));
        assertThat(schedule.get(3).getDueDate()).isEqualTo(first.plusWeeks(3));
    }

    @Test
    void biweekly_dueDatesStepByTwoWeeks() {
        List<Installment> schedule = engine.generate(
                scheduleId, new BigDecimal("10000"), new BigDecimal("26.0"), 3,
                "FRENCH", "BIWEEKLY", first);
        assertThat(schedule.get(1).getDueDate()).isEqualTo(first.plusWeeks(2));
        assertThat(schedule.get(2).getDueDate()).isEqualTo(first.plusWeeks(4));
    }

    @Test
    void monthly_dueDatesStepByMonth() {
        List<Installment> schedule = engine.generate(
                scheduleId, new BigDecimal("30000"), new BigDecimal("12.0"), 3,
                "FRENCH", "MONTHLY", first);
        assertThat(schedule.get(1).getDueDate()).isEqualTo(first.plusMonths(1));
        assertThat(schedule.get(2).getDueDate()).isEqualTo(first.plusMonths(2));
    }

    @Test
    void nullFrequencyAndType_defaultsToFrenchMonthly() {
        List<Installment> schedule = engine.generate(
                scheduleId, new BigDecimal("12000"), new BigDecimal("0.24"), 6,
                null, null, first);
        assertThat(schedule).hasSize(6);
        assertThat(schedule.get(1).getDueDate()).isEqualTo(first.plusMonths(1));
    }
}
