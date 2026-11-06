package com.fintech.salesorg.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlacementPolicyTest {

    static final ProductEnvelope LINE = new ProductEnvelope("DL-001", new BigDecimal("1000"), new BigDecimal("10000"), 12, 24);

    static ClientLimits limits(String amount, String outstanding, Integer loans, Integer term) {
        return new ClientLimits(amount == null ? null : new BigDecimal(amount), outstanding == null ? null : new BigDecimal(outstanding), loans, term);
    }

    @Test
    void aChildOverridesOnlyWhatItDeclares() {
        PlacementPolicy branch = PlacementPolicy.of(limits("5000", "5000", 1, 16), limits("10000", "15000", 3, 24));
        PlacementPolicy zoneWide = PlacementPolicy.of(limits("3000", null, null, null), null);

        PlacementPolicy effective = branch.overriddenBy(zoneWide);

        assertThat(effective.forType(ClientType.NUEVO)).isEqualTo(limits("3000", "5000", 1, 16));
        assertThat(effective.forType(ClientType.REVOLVENTE)).isEqualTo(limits("10000", "15000", 3, 24));
        assertThat(branch.overriddenBy(PlacementPolicy.EMPTY)).isEqualTo(branch);
    }

    @Test
    void aDistributorOnlyTightens_theStricterOfEachLimitWins() {
        PlacementPolicy branch = PlacementPolicy.of(limits("5000", "5000", 1, 16), limits("10000", "15000", 3, 24));
        PlacementPolicy hers = PlacementPolicy.of(limits("8000", "4000", 2, 12), limits("6000", null, null, 24));

        PlacementPolicy effective = branch.tightenedBy(hers);

        // Her wider numbers (8000, 2 loans) lose; her stricter ones (4000 outstanding, 12 fortnights, 6000) win.
        assertThat(effective.forType(ClientType.NUEVO)).isEqualTo(limits("5000", "4000", 1, 12));
        assertThat(effective.forType(ClientType.REVOLVENTE)).isEqualTo(limits("6000", "15000", 3, 24));
        assertThat(hers.widerThan(branch)).containsExactly("NUEVO: maxLoanAmount 8000 > 5000", "NUEVO: maxActiveLoans 2 > 1");
        // Without a branch policy she may declare whatever fits the product.
        assertThat(PlacementPolicy.EMPTY.tightenedBy(hers)).isEqualTo(hers);
        assertThat(hers.widerThan(PlacementPolicy.EMPTY)).isEmpty();
    }

    @Test
    void thePolicyMustFitInsideTheProductAndMakeSense() {
        PlacementPolicy fits = PlacementPolicy.of(limits("5000", "5000", 1, 16), limits("10000", "15000", 3, 24));
        assertThat(fits.violations(LINE)).isEmpty();

        PlacementPolicy wrong = PlacementPolicy.of(limits("12000", "4000", 0, 30), limits("500", "500", 1, 6));
        assertThat(wrong.violations(LINE)).containsExactly(
                "NUEVO: maxLoanAmount 12000 rebasa el máximo del producto 10000",
                "NUEVO: maxOutstanding 4000 no alcanza para un préstamo de 12000",
                "NUEVO: maxActiveLoans debe ser al menos 1",
                "NUEVO: maxTerm 30 rebasa el plazo máximo del producto 24",
                "REVOLVENTE: maxLoanAmount 500 queda bajo el mínimo del producto 1000",
                "REVOLVENTE: maxTerm 6 queda bajo el plazo mínimo del producto 12");
    }

    @Test
    void emptyLimitsAreDroppedAndCompletenessNeedsBothTypes() {
        PlacementPolicy p = new PlacementPolicy(Map.of(ClientType.NUEVO, ClientLimits.NONE, ClientType.REVOLVENTE, limits("10000", "15000", 3, 24)));
        assertThat(p.limits()).containsOnlyKeys(ClientType.REVOLVENTE);
        assertThat(p.isComplete()).isFalse();
        assertThat(PlacementPolicy.of(limits("5000", "5000", 1, 16), limits("10000", "15000", 3, 24)).isComplete()).isTrue();
        assertThat(PlacementPolicy.of(null, null).isEmpty()).isTrue();
    }

    @Test
    void resolvingAChainOfSixLevelsIsCheap() {
        PlacementPolicy[] chain = {
                PlacementPolicy.of(limits("10000", "20000", 3, 24), limits("10000", "20000", 3, 24)),
                PlacementPolicy.EMPTY,
                PlacementPolicy.of(limits("8000", null, null, null), null),
                PlacementPolicy.of(limits("5000", "5000", 1, 16), limits("10000", "15000", 3, 24)),
                PlacementPolicy.EMPTY,
                PlacementPolicy.of(limits("3000", null, 1, 12), null)};
        long start = System.nanoTime();
        PlacementPolicy last = null;
        for (int i = 0; i < 100_000; i++) {
            PlacementPolicy e = PlacementPolicy.EMPTY;
            for (int level = 0; level < chain.length; level++) {
                e = level == chain.length - 1 ? e.tightenedBy(chain[level]) : e.overriddenBy(chain[level]);
            }
            last = e;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(last.forType(ClientType.NUEVO)).isEqualTo(limits("3000", "5000", 1, 12));
        // 100,000 resolutions of a full chain: well under a second, so publishing a region's subtree costs nothing here.
        assertThat(ms).isLessThan(1000);
    }
}
