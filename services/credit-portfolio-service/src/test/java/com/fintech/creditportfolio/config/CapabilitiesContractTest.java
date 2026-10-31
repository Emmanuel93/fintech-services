package com.fintech.creditportfolio.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.PaymentOptions;
import org.junit.jupiter.api.Test;

/**
 * El contrato de capacidades entre catálogo y cartera: cartera lee lo que el catálogo publica —y lo
 * que ella misma guardó— aunque traiga campos que no conoce.
 *
 * <p>Se rompió en vivo: las bandas de MSI llegaban con {@code interestFree} (un getter derivado que
 * Jackson publicaba), cartera lo guardaba en su JSONB y después no podía leer la configuración de la
 * tarjeta ni del revolvente. La activación de esos productos se descartaba tras diez reintentos.
 */
class CapabilitiesContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** La configuración real de la tarjeta, tal como estaba guardada en product_config_versions. */
    private static final String TARJETA = """
            {"hasAmortizationSchedule":false,"hasCreditLimit":true,"allowsMultipleDispositions":true,
             "dispositionType":"SELF_USE","hasCutoffDate":true,"hasMinimumPayment":true,
             "allowsMultipleObligors":false,"commissionsEnabled":false,"requiresBeneficiaryPartyId":false,
             "opcionesDePago":{"skipMode":"DEFERRAL","bnplEnabled":false,
               "deferralRates":[{"maxTerm":6,"minTerm":3,"nominalRate":0.0000,"interestFree":true},
                                {"maxTerm":9,"minTerm":7,"nominalRate":0.1800,"interestFree":false}],
               "reliefEligible":true,"deferralMaxTerm":12,"deferralMinTerm":3,"maxSkipsPerCycle":1,
               "deferralCutoffRule":"BEFORE_CUTOFF","skipPaymentEnabled":true,"bnplMaxDeferralDays":null,
               "deferralDefaultTerm":3,"installmentPlanMode":"POST_HOC","campoQueAunNoExiste":"x"}}
            """;

    @Test
    void lee_la_configuracion_de_la_tarjeta_con_campos_que_no_conoce() throws Exception {
        Capabilities c = mapper.readValue(TARJETA, Capabilities.class);

        PaymentOptions o = c.opcionesDePago();
        assertThat(o.deferralRates()).hasSize(2);
        assertThat(o.deferralRates().get(0).isInterestFree()).isTrue();
        assertThat(o.deferralRates().get(1).isInterestFree()).isFalse();
    }

    @Test
    void el_derivado_no_se_vuelve_a_escribir() throws Exception {
        Capabilities c = mapper.readValue(TARJETA, Capabilities.class);

        String json = mapper.writeValueAsString(c);

        assertThat(json).doesNotContain("interestFree");
        assertThat(mapper.readValue(json, Capabilities.class)).isEqualTo(c);
    }

    @Test
    void leer_la_configuracion_es_inmediato() throws Exception {
        long inicio = System.nanoTime();
        for (int i = 0; i < 2_000; i++) mapper.readValue(TARJETA, Capabilities.class);
        // Rendimiento: se lee en cada operación de cartera que consulta su configuración.
        assertThat((System.nanoTime() - inicio) / 1_000_000).isLessThan(3_000L);
    }
}
