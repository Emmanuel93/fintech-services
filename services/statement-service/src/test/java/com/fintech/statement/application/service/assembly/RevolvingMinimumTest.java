package com.fintech.statement.application.service.assembly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The revolving statement asks for what the portfolio bills (CycleBillingService): closing's minimum up to
 * what is revolving, plus the plans' installments this statement bills.
 */
class RevolvingMinimumTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    @DisplayName("tarjeta sin planes: el mínimo y el pago para no generar intereses son los de closing")
    void plainCard() {
        BigDecimal minimo = RevolvingStatementAssembler.minimumDue(d("591.36"), d("11827.12"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(minimo).isEqualByComparingTo("591.36");
        assertThat(RevolvingStatementAssembler.noInterestPayment(d("11827.12"), BigDecimal.ZERO, BigDecimal.ZERO, minimo))
                .isEqualByComparingTo("11827.12");
    }

    @Test
    @DisplayName("tarjeta con meses sin intereses: el mínimo suma la mensualidad; no generar intereses no adelanta el plan")
    void cardWithMonthsWithoutInterest() {
        // Balance 9,288.84; the deferred purchase still owes 6,000 and this statement bills one month of 1,000.
        BigDecimal minimo = RevolvingStatementAssembler.minimumDue(d("464.44"), d("9288.84"), d("6000"), d("1000"));
        assertThat(minimo).isEqualByComparingTo("1464.44");
        assertThat(RevolvingStatementAssembler.noInterestPayment(d("9288.84"), d("6000"), d("1000"), minimo))
                .isEqualByComparingTo("4288.84");
    }

    @Test
    @DisplayName("línea con todo a plazos: se pide la mensualidad, no el 5 % del saldo")
    void lineWithEverythingAmortized() {
        BigDecimal minimo = RevolvingStatementAssembler.minimumDue(d("10179.80"), d("203596"), d("223334.88"), d("18611.24"));
        assertThat(minimo).isEqualByComparingTo("18611.24");
        assertThat(RevolvingStatementAssembler.noInterestPayment(d("203596"), d("223334.88"), d("18611.24"), minimo))
                .isEqualByComparingTo("18611.24");
    }

    @Test
    @DisplayName("sin pago mínimo en la política no se inventa uno")
    void noMinimumPolicy() {
        assertThat(RevolvingStatementAssembler.minimumDue(null, d("5000"), BigDecimal.ZERO, BigDecimal.ZERO)).isNull();
    }
}
