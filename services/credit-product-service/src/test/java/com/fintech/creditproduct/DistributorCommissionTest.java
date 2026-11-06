package com.fintech.creditproduct;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.creditproduct.domain.DistributorCommission;
import com.fintech.creditproduct.domain.DistributorCommission.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The distributor's commission terms as the catalog declares them: the market's ladder (18 / 14 / 11 / 7 / nothing by
 * the day she pays), netted from what she delivers, and what a configuration must respect to be published.
 */
class DistributorCommissionTest {

    @Test
    @DisplayName("la escalera del mercado: 18 % a tiempo, 14 % un día tarde, 11 % dos, 7 % tres, neteada")
    void theMarketLadder() {
        DistributorCommission c = DistributorCommission.marketLadder();
        assertThat(c.rate()).isEqualByComparingTo("0.18");
        assertThat(c.tiers()).extracting(Tier::daysLate).containsExactly(0, 1, 2, 3);
        assertThat(c.tiers()).extracting(Tier::rate).containsExactly(new BigDecimal("0.18"), new BigDecimal("0.14"),
                new BigDecimal("0.11"), new BigDecimal("0.07"));
        assertThat(c.settlement()).isEqualTo(DistributorCommission.NETTED);
        assertThat(c.basis()).isEqualTo(DistributorCommission.COLLECTED_TOTAL);
        assertThat(c.inconsistency()).isEmpty();
    }

    @Test
    @DisplayName("una configuración incoherente dice por qué: tasa fuera de rango, primer escalón que no es el día 0, escalón que paga más que el anterior")
    void anInconsistentConfigurationSaysWhy() {
        assertThat(new DistributorCommission(null, new BigDecimal("1.5"), List.of(), null).inconsistency())
                .contains("rate must be between 0 and 1");
        assertThat(new DistributorCommission(null, new BigDecimal("0.18"),
                List.of(new Tier(1, new BigDecimal("0.18"))), null).inconsistency())
                .contains("the first tier must be day 0, the payment on time");
        assertThat(new DistributorCommission(null, new BigDecimal("0.18"),
                List.of(new Tier(0, new BigDecimal("0.18")), new Tier(1, new BigDecimal("0.20"))), null).inconsistency())
                .contains("a later tier cannot pay more than an earlier one");
        assertThat(new DistributorCommission("OTHER", new BigDecimal("0.18"), List.of(), null).inconsistency())
                .contains("basis must be COLLECTED_PRINCIPAL_AND_INTEREST or COLLECTED_TOTAL");
        assertThat(new DistributorCommission(null, new BigDecimal("0.18"), List.of(), "CASH").inconsistency())
                .contains("settlement must be NETTED or PAYOUT");
    }

    @Test
    @DisplayName("viaja como JSON tal como lo guarda el catálogo y lo lee cartera")
    void travelsAsJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(DistributorCommission.marketLadder());
        assertThat(json).contains("\"rate\":0.18").contains("\"daysLate\":3").contains("\"settlement\":\"NETTED\"");
        DistributorCommission back = mapper.readValue(json, DistributorCommission.class);
        assertThat(back).isEqualTo(DistributorCommission.marketLadder());
        // A field this version does not know is ignored, as every other product configuration record does.
        DistributorCommission tolerant = mapper.readValue(
                "{\"rate\":0.18,\"tiers\":[{\"daysLate\":0,\"rate\":0.18,\"note\":\"x\"}],\"future\":1}",
                DistributorCommission.class);
        assertThat(tolerant.tiers()).hasSize(1);
    }

    @Test
    @DisplayName("rendimiento: validar un millón de configuraciones toma menos de un segundo")
    void aMillionValidationsInUnderASecond() {
        DistributorCommission c = DistributorCommission.marketLadder();
        long start = System.nanoTime();
        int ok = 0;
        for (int i = 0; i < 1_000_000; i++) {
            if (c.inconsistency().isEmpty()) ok++;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(ok).isEqualTo(1_000_000);
        assertThat(ms).as("1,000,000 validations took %d ms", ms).isLessThan(1_000);
    }
}
