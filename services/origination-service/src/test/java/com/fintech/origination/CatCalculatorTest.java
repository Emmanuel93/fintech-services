package com.fintech.origination;

import com.fintech.origination.application.service.CatCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CatCalculatorTest {

    @Test
    void installment_24pct_nominalRate_12months_yields_reasonable_cat() {
        BigDecimal cat = CatCalculator.calculateInstallment(
                new BigDecimal("50000"), new BigDecimal("24.0"), 12, new BigDecimal("3.0"));
        // CAT should be > nominal rate (24%) due to opening fee
        assertThat(cat).isGreaterThan(new BigDecimal("24.00"));
        // And not unreasonably high
        assertThat(cat).isLessThan(new BigDecimal("60.00"));
    }

    @Test
    void installment_zero_opening_fee_cat_equals_nominal_rate_approximately() {
        BigDecimal cat = CatCalculator.calculateInstallment(
                new BigDecimal("10000"), new BigDecimal("18.0"), 12, BigDecimal.ZERO);
        // With no fees, CAT ≈ nominal rate
        assertThat(cat).isGreaterThanOrEqualTo(new BigDecimal("18.00"));
        assertThat(cat).isLessThan(new BigDecimal("22.00"));
    }

    @Test
    void revolving_cat_is_the_nominal_rate_compounded_monthly() {
        // It used to return the nominal rate itself (36.00), which understates the cost: (1 + 0.36 / 12)^12 − 1.
        BigDecimal cat = CatCalculator.calculateRevolving(new BigDecimal("36.0"));
        assertThat(cat).isEqualByComparingTo(new BigDecimal("42.58"));
    }

    @Test
    void a_distributor_line_paid_by_fortnights_is_valued_with_24_periods_a_year() {
        // Circular 21/2009, 5.4.1 and 5.1 f: the line drawn in full and drawn again after every payment, a year of 24
        // fortnights. At 95 % the CAT is (1 + 0.95 / 24)^24 − 1 = 153.88 %; valued monthly it read 149.50 %.
        assertThat(CatCalculator.calculateRevolving(new BigDecimal("95.0"), 24)).isEqualByComparingTo("153.88");
        assertThat(CatCalculator.calculateRevolving(new BigDecimal("95.0"))).isEqualByComparingTo("149.50");
        assertThat(CatCalculator.calculateRevolving(new BigDecimal("95.0"), 0)).isEqualByComparingTo("0");
    }

    @Test
    void ten_thousand_revolving_cats_take_milliseconds() {
        // Performance: an offer is priced on every slider move; ten thousand stay far under a second.
        long start = System.nanoTime();
        BigDecimal last = BigDecimal.ZERO;
        for (int i = 0; i < 10_000; i++) {
            last = CatCalculator.calculateRevolving(new BigDecimal("95.0"), i % 2 == 0 ? 24 : 12);
        }
        assertThat(last.signum()).isPositive();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_000);
    }

    @Test
    void a_biweekly_plan_is_valued_with_26_periods_a_year() {
        // 20 % paid biweekly without fees: (1 + 0.20 / 26)^26 − 1 = 22.05 %, not the monthly 21.94 %.
        BigDecimal cat = CatCalculator.calculateInstallment(
                new BigDecimal("20000"), new BigDecimal("20.0"), 6, BigDecimal.ZERO, 26);
        assertThat(cat).isEqualByComparingTo(new BigDecimal("22.05"));
    }

    @Test
    void frequencies_count_as_the_portfolio_amortizes() {
        assertThat(CatCalculator.periodsPerYear("WEEKLY")).isEqualTo(52);
        assertThat(CatCalculator.periodsPerYear("BIWEEKLY")).isEqualTo(26);
        // The calendar fortnight (a distributor's placements): two a month.
        assertThat(CatCalculator.periodsPerYear("SEMI_MONTHLY")).isEqualTo(24);
        assertThat(CatCalculator.periodsPerYear("MONTHLY")).isEqualTo(12);
    }

    @Test
    void the_catalogs_fractions_become_percentages_before_the_calculator() {
        // The catalog keeps 0.32 for 32 % and 0.0100 for a 1 % fee; passed as they were, a 32 % personal loan's
        // offer showed a CAT of 0.4 %.
        assertThat(CatCalculator.percent(new BigDecimal("0.32")))
                .isEqualByComparingTo("32");
        BigDecimal cat = CatCalculator.calculateInstallment(new BigDecimal("12000"),
                CatCalculator.percent(new BigDecimal("0.32")), 6,
                CatCalculator.percent(new BigDecimal("0.0100")), 12);
        assertThat(cat).isBetween(new BigDecimal("41"), new BigDecimal("43"));   // 37.1 % compounded + the fee over 6 months
    }

    @Test
    void installment_null_opening_fee_treated_as_zero() {
        BigDecimal cat = CatCalculator.calculateInstallment(
                new BigDecimal("50000"), new BigDecimal("12.0"), 24, null);
        assertThat(cat).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void invalid_term_returns_zero() {
        BigDecimal cat = CatCalculator.calculateInstallment(
                new BigDecimal("50000"), new BigDecimal("24.0"), 0, BigDecimal.ZERO);
        assertThat(cat).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void longer_term_increases_cat_for_same_nominal_rate_and_fee() {
        BigDecimal cat12 = CatCalculator.calculateInstallment(
                new BigDecimal("50000"), new BigDecimal("24.0"), 12, new BigDecimal("3.0"));
        BigDecimal cat24 = CatCalculator.calculateInstallment(
                new BigDecimal("50000"), new BigDecimal("24.0"), 24, new BigDecimal("3.0"));
        // Opening fee amortised over longer term — slightly less impact on CAT
        assertThat(cat12).isGreaterThan(cat24);
    }
}
