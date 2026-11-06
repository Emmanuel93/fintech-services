package com.fintech.creditportfolio.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** The card minimum with the Banxico floor (C13/2011, 2.1): the product's share, never below the floor, never above the balance. */
class CardMinimumPaymentTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    @DisplayName("the product's 5 % stands when it is above the floor (E34: 10,348 owed, 348 of it interest and IVA)")
    void productMinimumAboveTheFloor() {
        // Floor: 1.5 % × 10,000 + 348 = 498; 1.25 % × 30,000 = 375. The product asks 517.40.
        assertThat(CardMinimumPayment.revolvingMinimum(d("517.40"), d("10348"), d("348"), d("30000")))
                .isEqualByComparingTo("517.40");
    }

    @Test
    @DisplayName("a small balance on a big line pays 1.25 % of the line, not the product's 5 % of the balance")
    void lineShareFloor() {
        // 5 % × 4,001.60 = 200.08 < 1.25 % × 30,000 = 375.
        assertThat(CardMinimumPayment.revolvingMinimum(d("200.08"), d("4001.60"), d("1.60"), d("30000")))
                .isEqualByComparingTo("375.00");
    }

    @Test
    @DisplayName("interest and IVA are always in the minimum: 1.5 % of the principal plus all of them")
    void interestIsAlwaysInTheMinimum() {
        // 1.5 % × 20,000 + 900 = 1,200 > 1.25 % × 10,000 = 125 > the product's 1 % (209).
        assertThat(CardMinimumPayment.revolvingMinimum(d("209.00"), d("20900"), d("900"), d("10000")))
                .isEqualByComparingTo("1200.00");
    }

    @Test
    @DisplayName("never more than what is revolving")
    void neverAboveTheBalance() {
        assertThat(CardMinimumPayment.revolvingMinimum(d("5.00"), d("100"), BigDecimal.ZERO, d("30000")))
                .isEqualByComparingTo("100.00");
        assertThat(CardMinimumPayment.revolvingMinimum(null, BigDecimal.ZERO, BigDecimal.ZERO, d("30000")))
                .isEqualByComparingTo("0.00");
    }
}
