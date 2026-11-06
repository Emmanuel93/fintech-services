package com.fintech.stp.infrastructure.adapter.out.http.stub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which order plays which scenario. Every amount picks one by its cents in the connector's tests; in the local stack
 * only the amounts a test armed do, and a commission lot of $754.10 settles instead of timing out.
 */
class StubScenarioSelectionTest {

    private static final Predicate<BigDecimal> NONE_ARMED = a -> false;

    @Test
    @DisplayName("cualquier importe elige su escenario por los centavos")
    void anyAmountPicksItsScenarioByItsCents() {
        assertThat(StubScenario.forAmount(new BigDecimal("754.10"))).isEqualTo(StubScenario.TIMEOUT);
        assertThat(StubScenario.forAmount(new BigDecimal("721.05"))).isEqualTo(StubScenario.CANCELLED);
        assertThat(StubScenario.forAmount(new BigDecimal("529.06"))).isEqualTo(StubScenario.NEVER_SETTLES);
        assertThat(StubScenario.forAmount(new BigDecimal("4000.00"))).isEqualTo(StubScenario.SETTLED);
    }

    @Test
    @DisplayName("armados: un importe calculado con centavos de falla se liquida; uno armado juega su escenario")
    void armedOnlyTheArmedAmountsPlayTheirScenario() {
        Predicate<BigDecimal> armed = Set.of(new BigDecimal("4000.02"), new BigDecimal("3000.04"))::contains;

        // The three lots the distributor suite of 2026-11-04 lost.
        assertThat(StubScenario.forAmount(new BigDecimal("754.10"), armed)).isEqualTo(StubScenario.SETTLED);
        assertThat(StubScenario.forAmount(new BigDecimal("721.05"), armed)).isEqualTo(StubScenario.SETTLED);
        assertThat(StubScenario.forAmount(new BigDecimal("529.06"), armed)).isEqualTo(StubScenario.SETTLED);
        // The scenarios the suite chose.
        assertThat(StubScenario.forAmount(new BigDecimal("4000.02"), armed)).isEqualTo(StubScenario.REJECTED_PLD);
        assertThat(StubScenario.forAmount(new BigDecimal("3000.04"), armed)).isEqualTo(StubScenario.RETURNED);
        assertThat(StubScenario.forAmount(new BigDecimal("4000.02"), NONE_ARMED)).isEqualTo(StubScenario.SETTLED);
    }

    @Test
    @DisplayName("el conjunto armado sólo se consulta cuando los centavos nombran un escenario")
    void theArmedSetIsAskedOnlyWhenTheCentsNameAScenario() {
        AtomicInteger asked = new AtomicInteger();
        Predicate<BigDecimal> counting = a -> { asked.incrementAndGet(); return false; };

        StubScenario.forAmount(new BigDecimal("1000.00"), counting);
        StubScenario.forAmount(new BigDecimal("1000.50"), counting);
        assertThat(asked).hasValue(0);
        StubScenario.forAmount(new BigDecimal("1000.02"), counting);
        assertThat(asked).hasValue(1);
    }

    @Test
    @DisplayName("rendimiento: un millón de órdenes eligen escenario en menos de un segundo")
    void aMillionSelectionsInUnderASecond() {
        Predicate<BigDecimal> armed = Set.of(new BigDecimal("4000.02"))::contains;
        long start = System.nanoTime();
        int failures = 0;
        for (int i = 0; i < 1_000_000; i++) {
            if (StubScenario.forAmount(BigDecimal.valueOf(100_000L + i, 2), armed) != StubScenario.SETTLED) failures++;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        // From $1,000.00 to $10,999.99 a ninth of the amounts carry a failure's cents; only the armed one fails.
        assertThat(failures).isEqualTo(1);
        assertThat(ms).as("un millón de selecciones tardaron " + ms + " ms").isLessThan(1_000);
    }
}
