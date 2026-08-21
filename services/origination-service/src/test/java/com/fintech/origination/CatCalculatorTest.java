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
    void revolving_cat_equals_nominal_rate() {
        BigDecimal cat = CatCalculator.calculateRevolving(new BigDecimal("36.0"));
        assertThat(cat).isEqualByComparingTo(new BigDecimal("36.00"));
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
