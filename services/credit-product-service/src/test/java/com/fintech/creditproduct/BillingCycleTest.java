package com.fintech.creditproduct;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.creditproduct.domain.BillingCycle;
import com.fintech.creditproduct.domain.ProductType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The billing cycle is product configuration: defaults per type, validated, and round-trips as JSON. */
class BillingCycleTest {

    @Test
    void defaultsReproduceWhatClosingAppliedBeforeTheCycleWasConfigurable() {
        assertThat(BillingCycle.defaultFor(ProductType.PERSONAL_LOAN))
                .isEqualTo(new BillingCycle("INSTALLMENT_DUE_DATE", null, 3, 3, null, "NEXT"));
        // A distributor line cuts on the 6th and the 21st, pays by the 18th and the 3rd, with no minimum.
        assertThat(BillingCycle.defaultFor(ProductType.DISTRIBUTOR_LINE))
                .isEqualTo(new BillingCycle("SEMI_MONTHLY", null, 0, 2, null, "NEXT", BillingCycle.PER_PERIOD,
                        List.of(6, 21), List.of(18, 3)));
        assertThat(BillingCycle.defaultFor(ProductType.CREDIT_CARD).paymentDueOffsetDays()).isEqualTo(20);
        for (ProductType type : ProductType.values()) {
            assertThat(BillingCycle.defaultFor(type).inconsistency()).as(type.name()).isEmpty();
        }
    }

    @Test
    void aFixedDayNeedsItsDayAndRatesStayInRange() {
        assertThat(new BillingCycle("DAY_OF_MONTH", null, 10, 3, null, "NEXT").inconsistency()).isPresent();
        assertThat(new BillingCycle("DAY_OF_MONTH", 31, 10, 3, null, "NEXT").inconsistency()).isPresent();
        assertThat(new BillingCycle("DAY_OF_WEEK", 5, 7, 2, null, "NEXT").inconsistency()).isEmpty();
        assertThat(new BillingCycle("DAY_OF_WEEK", 8, 7, 2, null, "NEXT").inconsistency()).isPresent();
        assertThat(new BillingCycle("CYCLE_FROM_ACTIVATION", null, 20, 3, new BigDecimal("1.5"), "NEXT").inconsistency()).isPresent();
        assertThat(new BillingCycle("CYCLE_FROM_ACTIVATION", null, -1, 3, null, "NEXT").inconsistency()).isPresent();
    }

    @Test
    void theFortnightCutsOnItsTwoDaysAndPaysBeforeTheNextCutoff() {
        // The reference model: cut on the 6th and the 21st, pay by the 18th and by the 3rd of the next month.
        assertThat(semiMonthly(List.of(6, 21), List.of(18, 3), 0).inconsistency()).isEmpty();
        // The calendar fortnight (the 15th and the last day: 31 is every month's last day), paying five days later.
        assertThat(semiMonthly(List.of(15, 31), null, 5).inconsistency()).isEmpty();
        // Its days are the two cutoffDays: a single cutoffDay is a contradiction.
        assertThat(new BillingCycle("SEMI_MONTHLY", 10, 0, 2, null, "NEXT", null, List.of(6, 21), List.of(18, 3))
                .inconsistency()).contains("SEMI_MONTHLY cuts on cutoffDays: no cutoffDay");
        assertThat(semiMonthly(null, List.of(18, 3), 0).inconsistency()).contains("SEMI_MONTHLY needs two cutoffDays");
        assertThat(semiMonthly(List.of(21, 6), List.of(3, 18), 0).inconsistency()).isPresent();
        // The 21st to the 6th of March is thirteen days in February: two days closer would leave eleven.
        assertThat(semiMonthly(List.of(6, 23), List.of(18, 3), 0).inconsistency())
                .contains("SEMI_MONTHLY cutoffDays must leave at least 13 days between cutoffs in every month");
        // Paying the 6th's statement on the 22nd lands after the cutoff of the 21st: two fortnights at once.
        assertThat(semiMonthly(List.of(6, 21), List.of(22, 3), 0).inconsistency())
                .hasValueSatisfying(r -> assertThat(r).startsWith("SEMI_MONTHLY pays after its cutoff and before the next one"));
        assertThat(semiMonthly(List.of(15, 31), null, 14).inconsistency()).isPresent();
        // Fixed days belong to the fortnight only.
        assertThat(new BillingCycle("DAY_OF_MONTH", 10, 10, 3, null, "NEXT", null, List.of(6, 21), null).inconsistency())
                .contains("cutoffDays and paymentDueDays belong to SEMI_MONTHLY only");
    }

    private static BillingCycle semiMonthly(List<Integer> cutoffDays, List<Integer> dueDays, int offset) {
        return new BillingCycle("SEMI_MONTHLY", null, offset, 2, null, "NEXT", BillingCycle.PER_PERIOD, cutoffDays, dueDays);
    }

    @Test
    void validatingEveryCycleOfAThousandProductsTakesMilliseconds() {
        // Performance: the console validates the cycle of every draft it saves and the catalog of every product it
        // lists. A thousand rounds over the nine product types stay well under a second.
        long start = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            for (ProductType type : ProductType.values()) {
                assertThat(BillingCycle.defaultFor(type).inconsistency()).isEmpty();
            }
        }
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_000);
    }

    @Test
    void roundTripsAsJsonAndIgnoresUnknownFields() throws Exception {
        ObjectMapper json = new ObjectMapper();
        BillingCycle weekly = new BillingCycle("DAY_OF_WEEK", 5, 7, 2, null, "NEXT");
        String text = json.writeValueAsString(weekly);
        assertThat(json.readValue(text, BillingCycle.class)).isEqualTo(weekly);
        BillingCycle fortnight = BillingCycle.defaultFor(ProductType.DISTRIBUTOR_LINE);
        assertThat(json.readValue(json.writeValueAsString(fortnight), BillingCycle.class)).isEqualTo(fortnight);
        assertThat(json.readValue("{\"cutoffRule\":\"NONE\",\"paymentDueOffsetDays\":0,\"graceDays\":0,\"extra\":1}", BillingCycle.class)
                .cutoffRule()).isEqualTo("NONE");
    }

    @Test
    void theInterestModeIsConfiguration_perPeriodForTheDistributorLine_dailyForTheRest() {
        assertThat(BillingCycle.defaultFor(ProductType.DISTRIBUTOR_LINE).effectiveInterestAccrual()).isEqualTo(BillingCycle.PER_PERIOD);
        assertThat(BillingCycle.defaultFor(ProductType.REVOLVING_LINE).effectiveInterestAccrual()).isEqualTo(BillingCycle.DAILY);
        assertThat(BillingCycle.defaultFor(ProductType.PERSONAL_LOAN).effectiveInterestAccrual()).isEqualTo(BillingCycle.DAILY);
        // A cycle published before the field existed is daily.
        assertThat(new BillingCycle("CYCLE_FROM_ACTIVATION", null, 15, 3, null, "NEXT", null).effectiveInterestAccrual())
                .isEqualTo(BillingCycle.DAILY);
        assertThat(new BillingCycle("CYCLE_FROM_ACTIVATION", null, 15, 3, null, "NEXT", "WEEKLY").inconsistency())
                .contains("interestAccrual must be DAILY or PER_PERIOD");
    }
}
