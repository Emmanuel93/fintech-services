package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.service.AmortizationEngine;
import com.fintech.creditportfolio.application.service.PlanCost;
import com.fintech.creditportfolio.domain.config.SemiMonthlyCalendar;
import com.fintech.creditportfolio.domain.Installment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A distributor's placement on the line's fortnight: twelve payments are twelve fortnights, each one due at a cutoff of
 * the line (the 6th or the 21st), interest of 95 / 24 per fortnight charged at the cutoff, the first fortnight in
 * proportion to the days the money was held, and the same payment every fortnight. Its CAT (Circular 21/2009) is the
 * rate's: about 153.9 %.
 */
class FortnightPlanTest {

    private static final BigDecimal RATE = new BigDecimal("0.95");
    private static final BigDecimal IVA = new BigDecimal("0.16");
    /** The distributor line's days: cutoffs on the 6th and the 21st, paid by the 18th and the 3rd. */
    private static final SemiMonthlyCalendar LINE = new SemiMonthlyCalendar(6, 21, 18, 3);
    private final AmortizationEngine engine = new AmortizationEngine();

    private List<Installment> placement(String amount, int fortnights, LocalDate placedOn) {
        return engine.generate(UUID.randomUUID(), new BigDecimal(amount), RATE, fortnights, "FRENCH", "SEMI_MONTHLY",
                placedOn, AmortizationEngine.firstDueDate(placedOn, "SEMI_MONTHLY", LINE), IVA, LINE);
    }

    @Test
    @DisplayName("12 pagos son 12 quincenas: cada cuota vence en un corte de la línea, el 6 o el 21")
    void twelvePaymentsAreTwelveFortnightsOnTheLinesCutoffs() {
        List<Installment> plan = placement("10000", 12, LocalDate.of(2025, 1, 10));

        assertThat(plan).hasSize(12);
        assertThat(plan).extracting(Installment::getDueDate).containsExactly(
                LocalDate.of(2025, 1, 21), LocalDate.of(2025, 2, 6), LocalDate.of(2025, 2, 21), LocalDate.of(2025, 3, 6),
                LocalDate.of(2025, 3, 21), LocalDate.of(2025, 4, 6), LocalDate.of(2025, 4, 21), LocalDate.of(2025, 5, 6),
                LocalDate.of(2025, 5, 21), LocalDate.of(2025, 6, 6), LocalDate.of(2025, 6, 21), LocalDate.of(2025, 7, 6));
        assertThat(plan).allMatch(i -> LINE.isCutoff(i.getDueDate()));
    }

    @Test
    @DisplayName("colocada el 10, la primera quincena cobra 11 de 15 días; la cuota es la misma las 12 veces")
    void theFirstFortnightBearsItsShareAndThePaymentStaysTheSame() {
        List<Installment> plan = placement("10000", 12, LocalDate.of(2025, 1, 10));

        // 10,000 × 95 % / 24 = 395.83 a fortnight; held 11 of the 15 days from the 6th to the 21st: 290.28.
        assertThat(plan.get(0).getInterestAmount()).isEqualByComparingTo("290.28");
        assertThat(plan.get(0).getTaxAmount()).isEqualByComparingTo("46.44");
        // The annuity solved over the shorter first period: 1,089.51 every fortnight, IVA included; the last one takes
        // the rounding residue.
        assertThat(plan.subList(0, 11)).allSatisfy(i -> assertThat(i.getTotalAmount()).isEqualByComparingTo("1089.51"));
        assertThat(plan.get(11).getTotalAmount()).isBetween(new BigDecimal("1089.31"), new BigDecimal("1089.71"));
        // From the second fortnight on, a whole fortnight of interest on the balance: 95 / 24 × 9,247.21.
        assertThat(plan.get(1).getInterestAmount()).isEqualByComparingTo("366.04");
        assertThat(plan.stream().map(Installment::getPrincipalAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("10000");
        assertThat(plan).allSatisfy(i -> assertThat(i.getTaxAmount())
                .isEqualByComparingTo(i.getInterestAmount().multiply(IVA).setScale(2, RoundingMode.HALF_UP)));
    }

    @Test
    @DisplayName("colocada el día de un corte, empieza a pagar en el corte siguiente con una quincena completa")
    void placedOnACutoffDayPaysAtTheNextOneAWholeFortnight() {
        List<Installment> onTheSixth = placement("10000", 12, LocalDate.of(2025, 1, 6));
        List<Installment> onTheSeventh = placement("10000", 12, LocalDate.of(2025, 1, 7));

        assertThat(onTheSixth.get(0).getDueDate()).isEqualTo(LocalDate.of(2025, 1, 21));
        assertThat(onTheSixth.get(0).getInterestAmount()).isEqualByComparingTo("395.83");
        assertThat(onTheSixth.get(0).getTotalAmount()).isEqualByComparingTo("1102.42");
        // The 7th holds the money 14 of the 15 days that end on the 21st.
        assertThat(onTheSeventh.get(0).getDueDate()).isEqualTo(LocalDate.of(2025, 1, 21));
        assertThat(onTheSeventh.get(0).getInterestAmount()).isEqualByComparingTo("369.44");
    }

    @Test
    @DisplayName("la quincena corta de febrero (del 21 al 6 de marzo, 13 días) cuenta sus propios días")
    void theShortFebruaryFortnightCountsItsOwnDays() {
        List<Installment> plan = placement("10000", 12, LocalDate.of(2025, 2, 22));
        // Held 12 of the 13 days from 21 February to 6 March.
        assertThat(plan.get(0).getDueDate()).isEqualTo(LocalDate.of(2025, 3, 6));
        assertThat(plan.get(0).getInterestAmount()).isEqualByComparingTo("365.38");
    }

    @Test
    @DisplayName("sin fecha de entrega, y en las demás cadencias, el plan es el de siempre")
    void otherCadencesAndPlansWithoutStartDateAreUnchanged() {
        LocalDate first = LocalDate.of(2025, 1, 21);
        List<Installment> withoutStart = engine.generate(UUID.randomUUID(), new BigDecimal("10000"), RATE, 12, "FRENCH",
                "SEMI_MONTHLY", null, first, IVA, LINE);
        assertThat(withoutStart.get(0).getInterestAmount()).isEqualByComparingTo("395.83");
        assertThat(withoutStart.get(1).getDueDate()).isEqualTo(LocalDate.of(2025, 2, 6));

        LocalDate start = LocalDate.of(2025, 1, 10);
        List<Installment> monthly = engine.generate(UUID.randomUUID(), new BigDecimal("10000"), new BigDecimal("0.32"), 6,
                "FRENCH", "MONTHLY", start, AmortizationEngine.firstDueDate(start, "MONTHLY"), IVA);
        List<Installment> monthlyBefore = engine.generate(UUID.randomUUID(), new BigDecimal("10000"), new BigDecimal("0.32"), 6,
                "FRENCH", "MONTHLY", AmortizationEngine.firstDueDate(start, "MONTHLY"), IVA);
        assertThat(monthly).extracting(Installment::getTotalAmount)
                .containsExactlyElementsOf(monthlyBefore.stream().map(Installment::getTotalAmount).toList());
        // Without the product's days, the fortnight is the calendar one: the 15th and the last day.
        assertThat(AmortizationEngine.firstDueDate(start, "SEMI_MONTHLY")).isEqualTo(LocalDate.of(2025, 1, 15));
    }

    @Test
    @DisplayName("una colocación chica a 6 quincenas también cuadra al centavo")
    void aSmallPlacementSquaresToTheCent() {
        List<Installment> plan = placement("1000", 6, LocalDate.of(2025, 2, 20));

        assertThat(plan.get(0).getDueDate()).isEqualTo(LocalDate.of(2025, 2, 21));
        assertThat(plan.get(0).getInterestAmount()).isEqualByComparingTo("2.64");   // one day of fifteen
        assertThat(plan.get(5).getDueDate()).isEqualTo(LocalDate.of(2025, 5, 6));
        assertThat(plan.stream().map(Installment::getPrincipalAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1000");
        assertThat(plan).allSatisfy(i -> assertThat(i.getPrincipalAmount().signum()).isPositive());
    }

    // ── The CAT of a placement (Circular 21/2009) ────────────────────────────────────────────────

    @Test
    @DisplayName("CAT: una colocación al 95 % por quincenas cuesta 153.9 % sin IVA, como la tasa")
    void aPlacementsCatIsTheRatesCat() {
        // A whole first fortnight: (1 + 0.95 / 24)^24 − 1, with the plan's own rounding.
        assertThat(PlanCost.cat(new BigDecimal("10000"), placement("10000", 12, LocalDate.of(2025, 1, 6)), 24))
                .isCloseTo(new BigDecimal("1.538762"), within(new BigDecimal("0.000005")));
        // Placed on the 10th: the first payment comes 11/15 of a fortnight after the money; counted so, the same cost.
        assertThat(PlanCost.cat(new BigDecimal("10000"), placement("10000", 12, LocalDate.of(2025, 1, 10)), 24,
                new BigDecimal(11).divide(new BigDecimal(15), MathContext.DECIMAL64)))
                .isCloseTo(new BigDecimal("1.540317"), within(new BigDecimal("0.000005")));
        // Placed the day before the cutoff: one day of interest. Counting its first payment a whole fortnight away
        // would read 96.7 %; a fortnight's fifteenth away, it is the rate's 154.0 %.
        List<Installment> oneDay = placement("1000", 6, LocalDate.of(2025, 2, 20));
        assertThat(PlanCost.cat(new BigDecimal("1000"), oneDay, 24, new BigDecimal(1).divide(new BigDecimal(15), MathContext.DECIMAL64)))
                .isCloseTo(new BigDecimal("1.540164"), within(new BigDecimal("0.000005")));
        assertThat(PlanCost.cat(new BigDecimal("1000"), oneDay, 24))
                .isCloseTo(new BigDecimal("0.967320"), within(new BigDecimal("0.000005")));
        // Twenty-four fortnights at 95 %: also 153.9 %.
        assertThat(PlanCost.cat(new BigDecimal("10000"), placement("10000", 24, LocalDate.of(2025, 1, 6)), 24))
                .isCloseTo(new BigDecimal("1.538772"), within(new BigDecimal("0.000005")));
    }

    @Test
    @DisplayName("CAT: un plan sin intereses no cuesta nada, y sin plan no hay CAT")
    void aPlanWithoutInterestCostsNothing() {
        List<Installment> free = engine.generate(UUID.randomUUID(), new BigDecimal("6000"), BigDecimal.ZERO, 6, "FRENCH",
                "MONTHLY", LocalDate.of(2025, 2, 1), IVA);
        assertThat(PlanCost.cat(new BigDecimal("6000"), free, 12)).isEqualByComparingTo("0");
        assertThat(PlanCost.cat(new BigDecimal("6000"), List.of(), 12)).isNull();
        assertThat(PlanCost.periodsPerYear("SEMI_MONTHLY")).isEqualTo(24);
        assertThat(PlanCost.periodsPerYear(null)).isEqualTo(12);
    }

    @Test
    @DisplayName("rendimiento: 10,000 colocaciones a 24 quincenas se calculan en menos de dos segundos")
    void tenThousandPlacementsInUnderTwoSeconds() {
        long start = System.nanoTime();
        int installments = 0;
        for (int i = 0; i < 10_000; i++) {
            installments += placement(String.valueOf(1_000 + (i % 19) * 500), 24, LocalDate.of(2025, 1, 1).plusDays(i % 365)).size();
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(installments).isEqualTo(240_000);
        assertThat(ms).as("10,000 planes tardaron " + ms + " ms").isLessThan(2_000);
    }

    @Test
    @DisplayName("rendimiento: el CAT de 10,000 colocaciones se calcula en menos de dos segundos")
    void tenThousandCatsInUnderTwoSeconds() {
        List<Installment> plan = placement("10000", 24, LocalDate.of(2025, 1, 10));
        long start = System.nanoTime();
        BigDecimal last = null;
        for (int i = 0; i < 10_000; i++) {
            last = PlanCost.cat(new BigDecimal("10000"), plan, 24, new BigDecimal("0.733333"));
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(last).isNotNull();
        assertThat(ms).as("10,000 CAT tardaron " + ms + " ms").isLessThan(2_000);
    }
}
