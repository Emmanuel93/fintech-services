package com.fintech.closing.billingcycle;

import com.fintech.closing.domain.PaymentCadence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La periodicidad del producto, y por qué «no configurada» dejó de ser «mensual».
 *
 * <h2>Lo que esta clase protege</h2>
 *
 * El método era {@code from(String)} y devolvía {@code MONTHLY} ante nulo <b>y</b> ante basura. Con
 * eso, dos hechos distintos —«el producto no declaró su periodicidad» y «el producto es mensual»—
 * producían el mismo resultado, y un producto configurado semanal cuyo dato no llegara cortaba
 * mensual <b>sin que nada fallara ni quedara registrado</b>.
 *
 * <p>Ahora devuelve {@code Optional} y el llamador decide: cae a mensual —que es lo que hace cartera
 * al amortizar, así que las dos coinciden— pero <b>lo dice en el log</b>.
 */
class PaymentCadenceTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 3, 10);

    // ── Lo que reconoce ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reconoce las tres periodicidades que el catálogo admite")
    void itrecognisesTheThreeConfigurableCadences() {
        // Son exactamente las que `credit_product.payment_frequencies` permite por CHECK.
        assertThat(PaymentCadence.parse("WEEKLY")).contains(PaymentCadence.WEEKLY);
        assertThat(PaymentCadence.parse("BIWEEKLY")).contains(PaymentCadence.BIWEEKLY);
        assertThat(PaymentCadence.parse("MONTHLY")).contains(PaymentCadence.MONTHLY);
    }

    @Test
    @DisplayName("tolera minúsculas y espacios: el dato viene de una columna, no de un enum")
    void itisForgivingAboutFormatting() {
        assertThat(PaymentCadence.parse("  weekly ")).contains(PaymentCadence.WEEKLY);
        assertThat(PaymentCadence.parse("Monthly")).contains(PaymentCadence.MONTHLY);
    }

    // ── Lo que NO reconoce, y es el punto ────────────────────────────────────────────────────

    @Test
    @DisplayName("sin dato devuelve VACÍO, no mensual")
    void anabsentCadenceIsEmptyNotMonthly() {
        assertThat(PaymentCadence.parse(null)).isEmpty();
        assertThat(PaymentCadence.parse("")).isEmpty();
        assertThat(PaymentCadence.parse("   ")).isEmpty();
    }

    @Test
    @DisplayName("una periodicidad desconocida también devuelve vacío")
    void anunknownCadenceIsEmptyToo() {
        // `TRIMESTRAL` es plausible como configuración y no existe en el catálogo. Antes caía a
        // mensual, con lo que el producto parecía configurado y cortaba otra cosa.
        assertThat(PaymentCadence.parse("TRIMESTRAL")).isEmpty();
        assertThat(PaymentCadence.parse("QUARTERLY")).isEmpty();
    }

    // ── Cómo avanza cada una ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cada cadencia avanza su propio período")
    void eachCadenceAdvancesItsOwnPeriod() {
        assertThat(PaymentCadence.WEEKLY.advance(TUESDAY, 1)).isEqualTo(TUESDAY.plusWeeks(1));
        assertThat(PaymentCadence.BIWEEKLY.advance(TUESDAY, 1)).isEqualTo(TUESDAY.plusWeeks(2));
        assertThat(PaymentCadence.MONTHLY.advance(TUESDAY, 1)).isEqualTo(TUESDAY.plusMonths(1));
    }

    @Test
    @DisplayName("quincenal es cada 14 días, NO «dos veces al mes»")
    void biweeklyMeansEveryFourteenDaysNotTwiceAmonth() {
        // La diferencia aparece al tercer período y se acumula: 26 períodos al año, no 24. Sobre un
        // plan de dos años son dos cortes de diferencia, y el último caería un mes antes.
        assertThat(PaymentCadence.BIWEEKLY.periodsPerYear()).isEqualTo(26);
        assertThat(PaymentCadence.BIWEEKLY.advance(LocalDate.of(2026, 1, 1), 2))
                .isEqualTo(LocalDate.of(2026, 1, 29));
    }

    @Test
    @DisplayName("los períodos por año son los que traducen un horizonte en años a número de cortes")
    void periodsPerYearTranslatesAhorizonIntoCutoffs() {
        // Es lo que permite que el calendario de un revolvente cubra dos años con cualquier
        // cadencia, en vez de «24 cortes» — que sobre un semanal se agotan en cinco meses.
        assertThat(PaymentCadence.WEEKLY.periodsPerYear()).isEqualTo(52);
        assertThat(PaymentCadence.MONTHLY.periodsPerYear()).isEqualTo(12);
    }
}
