package com.fintech.creditproduct.deferral;

import com.fintech.creditproduct.application.service.CreditProductCatalogService;
import com.fintech.creditproduct.domain.Capabilities;
import com.fintech.creditproduct.domain.CreditProductDefinition;
import com.fintech.creditproduct.domain.PaymentOptions;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qué declara cada producto del catálogo sembrado.
 *
 * <p>Los productos no declaraban <b>nada</b> de esto, y sin declararlo no lo tienen: el default de
 * {@code PaymentOptions} está todo apagado a propósito. Un default permisivo convertiría cada
 * producto viejo en uno que admite diferir y saltar pagos sin que nadie lo haya decidido.
 *
 * <p>Esta prueba fija la configuración del <b>seed</b>, no la del motor: si alguien retira las
 * bandas de MSI de la tarjeta, el escenario S2 deja de poder sembrarse y esto lo dice antes.
 */
@SpringBootTest
class PaymentOptionsByProductIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of(); }   // sólo lectura del seed

    @Autowired CreditProductCatalogService catalogo;

    private PaymentOptions optionsOf(String codigo) {
        CreditProductDefinition p = catalogo.findAll().stream()
                .filter(d -> codigo.equals(d.getProductCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("El catálogo no tiene " + codigo));
        return p.getCapabilities().options();
    }

    @Test
    @DisplayName("la tarjeta difiere DESPUÉS de la compra, y ofrece 3 y 6 meses sin intereses")
    void laTarjetaDifierePostHoc() {
        PaymentOptions o = optionsOf("CC-IND-STD-V1");

        // POST_HOC: la compra nace revolvente pura y el titular decide después. Es la mecánica de
        // una tarjeta, opuesta a la del distribuidor.
        assertThat(o.planPosterior()).isTrue();
        assertThat(o.allowsDeferral()).isTrue();
        assertThat(o.deferralTerm(null)).isEqualTo(3);

        assertThat(o.deferralRate(3)).get().isEqualTo(new BigDecimal("0.0000"));
        assertThat(o.deferralRate(6)).get().isEqualTo(new BigDecimal("0.0000"));
        assertThat(o.deferralRate(9)).get().isEqualTo(new BigDecimal("0.1800"));
        assertThat(o.deferralRate(12)).get().isEqualTo(new BigDecimal("0.2400"));
        // Fuera de las bandas no hay tasa, y sin tasa no se difiere: no se cae a la de originación.
        assertThat(o.deferralRate(24)).isEmpty();
    }

    @Test
    @DisplayName("la línea de distribuidor fija el plazo AL colocar, no después")
    void elDistribuidorFijaAlColocar() {
        PaymentOptions o = optionsOf("DL-DIST-STD-V1");

        // El vendedor decide «a cuántos meses se lo dejas» en el momento. Diferir después no aplica:
        // la beneficiaria ya firmó su plan.
        assertThat(o.planAtDisposition()).isTrue();
        assertThat(o.allowsDeferral()).isFalse();
    }

    @Test
    @DisplayName("el préstamo personal admite BNPL y salta pagos como REGALO")
    void elPersonalAdmiteBnplYRegalo() {
        PaymentOptions o = optionsOf("PL-IND-STD-V1");

        // No hay nada que diferir en un crédito que nace amortizado.
        assertThat(o.planPosterior()).isFalse();
        // Pero correr el arranque del pago sí es una decisión del alta.
        assertThat(o.bnplEnabled()).isTrue();
        assertThat(o.bnplMaxDeferralDays()).isEqualTo(30);
        // GIFT: el período saltado no devenga. Es una recompensa, no un aplazamiento.
        assertThat(o.skipPaymentEnabled()).isTrue();
        assertThat(o.skipIsGift()).isTrue();
    }

    @Test
    @DisplayName("un producto que NO declara opciones no las tiene")
    void sinDeclararNoLasTiene() {
        // El default apagado es lo que evita que un producto viejo herede facultades que nadie le
        // dio. El constructor de nueve argumentos —el que usan los seeds anteriores a BK-23— cae
        // aquí, y `oNinguna` lo garantiza aunque la fila venga de la base sin el bloque.
        PaymentOptions ninguna = new Capabilities(
                true, false, false, "SELF_USE", false, false, false, false, false).options();

        assertThat(ninguna.allowsDeferral()).isFalse();
        assertThat(ninguna.bnplEnabled()).isFalse();
        assertThat(ninguna.skipPaymentEnabled()).isFalse();
        assertThat(ninguna.reliefEligible()).isFalse();
    }
}
