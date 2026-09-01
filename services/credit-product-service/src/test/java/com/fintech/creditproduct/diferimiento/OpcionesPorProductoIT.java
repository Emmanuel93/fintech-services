package com.fintech.creditproduct.diferimiento;

import com.fintech.creditproduct.application.service.CreditProductCatalogService;
import com.fintech.creditproduct.domain.Capabilities;
import com.fintech.creditproduct.domain.CreditProductDefinition;
import com.fintech.creditproduct.domain.OpcionesDePago;
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
 * {@code OpcionesDePago} está todo apagado a propósito. Un default permisivo convertiría cada
 * producto viejo en uno que admite diferir y saltar pagos sin que nadie lo haya decidido.
 *
 * <p>Esta prueba fija la configuración del <b>seed</b>, no la del motor: si alguien retira las
 * bandas de MSI de la tarjeta, el escenario S2 deja de poder sembrarse y esto lo dice antes.
 */
@SpringBootTest
class OpcionesPorProductoIT extends AbstractIntegrationTest {

    @Override protected List<String> esquemas() { return List.of(); }   // sólo lectura del seed

    @Autowired CreditProductCatalogService catalogo;

    private OpcionesDePago opcionesDe(String codigo) {
        CreditProductDefinition p = catalogo.findAll().stream()
                .filter(d -> codigo.equals(d.getProductCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("El catálogo no tiene " + codigo));
        return p.getCapabilities().opciones();
    }

    @Test
    @DisplayName("la tarjeta difiere DESPUÉS de la compra, y ofrece 3 y 6 meses sin intereses")
    void laTarjetaDifierePostHoc() {
        OpcionesDePago o = opcionesDe("CC-IND-STD-V1");

        // POST_HOC: la compra nace revolvente pura y el titular decide después. Es la mecánica de
        // una tarjeta, opuesta a la del distribuidor.
        assertThat(o.planPosterior()).isTrue();
        assertThat(o.admiteDiferir()).isTrue();
        assertThat(o.plazoDeDiferimiento(null)).isEqualTo(3);

        assertThat(o.tasaDeDiferimiento(3)).get().isEqualTo(new BigDecimal("0.0000"));
        assertThat(o.tasaDeDiferimiento(6)).get().isEqualTo(new BigDecimal("0.0000"));
        assertThat(o.tasaDeDiferimiento(9)).get().isEqualTo(new BigDecimal("0.1800"));
        assertThat(o.tasaDeDiferimiento(12)).get().isEqualTo(new BigDecimal("0.2400"));
        // Fuera de las bandas no hay tasa, y sin tasa no se difiere: no se cae a la de originación.
        assertThat(o.tasaDeDiferimiento(24)).isEmpty();
    }

    @Test
    @DisplayName("la línea de distribuidor fija el plazo AL colocar, no después")
    void elDistribuidorFijaAlColocar() {
        OpcionesDePago o = opcionesDe("DL-DIST-STD-V1");

        // El vendedor decide «a cuántos meses se lo dejas» en el momento. Diferir después no aplica:
        // la beneficiaria ya firmó su plan.
        assertThat(o.planAlDisponer()).isTrue();
        assertThat(o.admiteDiferir()).isFalse();
    }

    @Test
    @DisplayName("el préstamo personal admite BNPL y salta pagos como REGALO")
    void elPersonalAdmiteBnplYRegalo() {
        OpcionesDePago o = opcionesDe("PL-IND-STD-V1");

        // No hay nada que diferir en un crédito que nace amortizado.
        assertThat(o.planPosterior()).isFalse();
        // Pero correr el arranque del pago sí es una decisión del alta.
        assertThat(o.bnplEnabled()).isTrue();
        assertThat(o.bnplMaxDeferralDays()).isEqualTo(30);
        // GIFT: el período saltado no devenga. Es una recompensa, no un aplazamiento.
        assertThat(o.skipPaymentEnabled()).isTrue();
        assertThat(o.saltoEsRegalo()).isTrue();
    }

    @Test
    @DisplayName("un producto que NO declara opciones no las tiene")
    void sinDeclararNoLasTiene() {
        // El default apagado es lo que evita que un producto viejo herede facultades que nadie le
        // dio. El constructor de nueve argumentos —el que usan los seeds anteriores a BK-23— cae
        // aquí, y `oNinguna` lo garantiza aunque la fila venga de la base sin el bloque.
        OpcionesDePago ninguna = new Capabilities(
                true, false, false, "SELF_USE", false, false, false, false, false).opciones();

        assertThat(ninguna.admiteDiferir()).isFalse();
        assertThat(ninguna.bnplEnabled()).isFalse();
        assertThat(ninguna.skipPaymentEnabled()).isFalse();
        assertThat(ninguna.reliefEligible()).isFalse();
    }
}
