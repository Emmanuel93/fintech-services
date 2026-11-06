package com.fintech.creditportfolio.config;

import com.fintech.creditportfolio.application.service.AmortizationEngine;
import com.fintech.creditportfolio.domain.DistributorCommissionSchedule;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.config.DistributorCommission;
import com.fintech.creditportfolio.domain.config.DistributorCommission.Tier;
import com.fintech.creditportfolio.domain.config.SemiMonthlyCalendar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The distributor's commission on a placement: 18 % of every installment her client pays, the same each fortnight,
 * paid out of the placement's interest and never out of its capital; less the later she delivers, nothing past the
 * ladder. The two numbers her statement shows: the share of what she collects and what that is of the interest.
 */
class DistributorCommissionScheduleTest {

    private static final BigDecimal RATE = new BigDecimal("0.95");
    private static final SemiMonthlyCalendar FORTNIGHT = SemiMonthlyCalendar.of(List.of(6, 21), List.of(18, 3));
    private static final DistributorCommission MARKET = new DistributorCommission(
            DistributorCommission.COLLECTED_TOTAL, new BigDecimal("0.18"),
            List.of(new Tier(0, new BigDecimal("0.18")), new Tier(1, new BigDecimal("0.14")),
                    new Tier(2, new BigDecimal("0.11")), new Tier(3, new BigDecimal("0.07"))),
            DistributorCommission.NETTED);

    /** A $5,000 placement made on 8 January 2025, paid on the fortnights' cutoffs. */
    private static List<Installment> placement(int fortnights) {
        LocalDate placed = LocalDate.of(2025, 1, 8);
        return new AmortizationEngine().generate(UUID.randomUUID(), new BigDecimal("5000.00"), RATE, fortnights,
                "FRENCH", "SEMI_MONTHLY", placed, AmortizationEngine.firstDueDate(placed, "SEMI_MONTHLY", FORTNIGHT),
                new BigDecimal("0.16"), FORTNIGHT);
    }

    @Test
    @DisplayName("12 quincenas: cada cuota lleva el 18 % de lo que paga el cliente, el mismo peso cada quincena")
    void twelveFortnightsAreFlat() {
        List<Installment> plan = DistributorCommissionSchedule.plan(placement(12), MARKET);
        BigDecimal flat = plan.get(1).getTotalAmount().multiply(new BigDecimal("0.18")).setScale(2, RoundingMode.HALF_UP);
        for (int k = 0; k < plan.size() - 1; k++) {
            Installment i = plan.get(k);
            assertThat(i.getScheduledCommission()).as("installment %d", i.getInstallmentNumber()).isEqualByComparingTo(flat);
        }
        // The plan's commission is the sum of what she keeps each fortnight, each one to the cent.
        BigDecimal expectedTotal = plan.stream().map(i -> i.getTotalAmount().multiply(new BigDecimal("0.18")).setScale(2, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commission = plan.stream().map(Installment::getScheduledCommission).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(commission).isEqualByComparingTo(expectedTotal);
        // The second number: 18 % of what she collects is about 86 % of the plan's interest at 12 fortnights.
        assertThat(DistributorCommissionSchedule.shareOfInterest(plan)).isBetween(new BigDecimal("0.80"), new BigDecimal("0.95"));
        assertThat(DistributorCommissionSchedule.interestCoversCommission(plan)).as("never out of the capital").isTrue();
    }

    @Test
    @DisplayName("colocada la víspera del corte: la primera quincena carga lo que su día de interés alcanza y el resto pasa a la siguiente")
    void aShortFirstPeriodDefersWhatItsInterestCannotCarry() {
        LocalDate placed = LocalDate.of(2025, 1, 20);   // the cutoff is the 21st: one day of interest
        List<Installment> plan = DistributorCommissionSchedule.plan(new AmortizationEngine().generate(UUID.randomUUID(),
                new BigDecimal("5000.00"), RATE, 12, "FRENCH", "SEMI_MONTHLY", placed,
                AmortizationEngine.firstDueDate(placed, "SEMI_MONTHLY", FORTNIGHT), new BigDecimal("0.16"), FORTNIGHT), MARKET);
        BigDecimal flat = plan.get(2).getTotalAmount().multiply(new BigDecimal("0.18")).setScale(2, RoundingMode.HALF_UP);
        Installment first = plan.get(0), second = plan.get(1);
        assertThat(first.getScheduledCommission()).isEqualByComparingTo(first.getInterestAmount()).isLessThan(flat);
        assertThat(first.getScheduledCommission().add(second.getScheduledCommission())).isEqualByComparingTo(flat.multiply(BigDecimal.TWO));
        assertThat(plan.get(2).getScheduledCommission()).isEqualByComparingTo(flat);
        BigDecimal expectedTotal = plan.stream().map(i -> i.getTotalAmount().multiply(new BigDecimal("0.18")).setScale(2, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commission = plan.stream().map(Installment::getScheduledCommission).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(commission).isEqualByComparingTo(expectedTotal);
        assertThat(DistributorCommissionSchedule.interestCoversCommission(plan)).isTrue();
    }

    @Test
    @DisplayName("24 quincenas: el 18 % de lo cobrado es cerca de la mitad del interés, y el interés siempre alcanza")
    void twentyFourFortnights() {
        List<Installment> plan = DistributorCommissionSchedule.plan(placement(24), MARKET);
        assertThat(DistributorCommissionSchedule.shareOfInterest(plan)).isBetween(new BigDecimal("0.45"), new BigDecimal("0.56"));
        assertThat(DistributorCommissionSchedule.interestCoversCommission(plan)).isTrue();
        assertThat(plan.stream().map(Installment::getScheduledCommission).distinct().count())
                .as("flat but for the first (shorter) period and the last (rounding)").isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("6 quincenas: el 18 % de lo cobrado rebasa el interés y la comisión se topa en él, pareja en todas las cuotas")
    void sixFortnightsAreCappedAtTheInterest() {
        List<Installment> plan = DistributorCommissionSchedule.plan(placement(6), MARKET);
        BigDecimal interest = plan.stream().map(Installment::getInterestAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commission = plan.stream().map(Installment::getScheduledCommission).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(commission).isEqualByComparingTo(interest);
        assertThat(DistributorCommissionSchedule.shareOfInterest(plan)).isEqualByComparingTo("1.0000");
        assertThat(DistributorCommissionSchedule.interestCoversCommission(plan)).isTrue();
        // Scaled alike: every installment keeps the same share of what the client pays.
        BigDecimal share1 = plan.get(1).getScheduledCommission().divide(plan.get(1).getTotalAmount(), 4, RoundingMode.HALF_UP);
        BigDecimal share3 = plan.get(3).getScheduledCommission().divide(plan.get(3).getTotalAmount(), 4, RoundingMode.HALF_UP);
        assertThat(share1.subtract(share3).abs()).isLessThan(new BigDecimal("0.0002"));
    }

    @Test
    @DisplayName("la escalera: 18 % a tiempo, 14 % un día tarde, 11 % dos, 7 % tres, nada después")
    void theLadder() {
        assertThat(MARKET.rateFor(-5)).isEqualByComparingTo("0.18");
        assertThat(MARKET.rateFor(0)).isEqualByComparingTo("0.18");
        assertThat(MARKET.rateFor(1)).isEqualByComparingTo("0.14");
        assertThat(MARKET.rateFor(2)).isEqualByComparingTo("0.11");
        assertThat(MARKET.rateFor(3)).isEqualByComparingTo("0.07");
        assertThat(MARKET.rateFor(4)).isEqualByComparingTo("0");
        assertThat(MARKET.rateFor(40)).isEqualByComparingTo("0");
        assertThat(MARKET.lastTierDay()).isEqualTo(3);
        // A commission scheduled at 100.00 on time becomes 77.78 a day late, 61.11 two, 38.89 three, nothing after.
        assertThat(MARKET.at(new BigDecimal("100.00"), 0)).isEqualByComparingTo("100.00");
        assertThat(MARKET.at(new BigDecimal("100.00"), 1)).isEqualByComparingTo("77.78");
        assertThat(MARKET.at(new BigDecimal("100.00"), 2)).isEqualByComparingTo("61.11");
        assertThat(MARKET.at(new BigDecimal("100.00"), 3)).isEqualByComparingTo("38.89");
        assertThat(MARKET.at(new BigDecimal("100.00"), 4)).isEqualByComparingTo("0");
        // Without tiers the rate holds on time and nothing is earned late.
        DistributorCommission flat = new DistributorCommission(null, new BigDecimal("0.18"), null, null);
        assertThat(flat.rateFor(0)).isEqualByComparingTo("0.18");
        assertThat(flat.rateFor(1)).isEqualByComparingTo("0");
        assertThat(flat.isNetted()).as("netted unless the product says PAYOUT").isTrue();
    }

    @Test
    @DisplayName("un producto sin comisión de distribuidora deja el plan como estaba")
    void aProductWithoutADistributorLeavesThePlanAlone() {
        List<Installment> plan = DistributorCommissionSchedule.plan(placement(12), null);
        assertThat(plan).allSatisfy(i -> assertThat(i.getScheduledCommission()).isNull());
        assertThat(DistributorCommissionSchedule.shareOfInterest(plan)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("rendimiento: diez mil planes de 24 quincenas se comisionan, y un millón de escalones se resuelven, en menos de un segundo cada uno")
    void performance() {
        List<Installment> base = placement(24);
        long start = System.nanoTime();
        for (int n = 0; n < 10_000; n++) {
            DistributorCommissionSchedule.plan(base, MARKET);
        }
        long planMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(planMs).as("10,000 plans took %d ms", planMs).isLessThan(1_000);

        BigDecimal onTime = new BigDecimal("123.45");
        start = System.nanoTime();
        BigDecimal sum = BigDecimal.ZERO;
        for (int n = 0; n < 1_000_000; n++) {
            sum = sum.add(MARKET.at(onTime, n % 6));
        }
        long tierMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(sum.signum()).isPositive();
        assertThat(tierMs).as("1,000,000 ladder steps took %d ms", tierMs).isLessThan(1_000);
    }
}
