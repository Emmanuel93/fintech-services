package com.fintech.creditproduct.diferimiento;

import com.fintech.creditproduct.domain.RateCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La tasa con la que se difiere una compra, por banda de plazo.
 *
 * <p>Un producto real ofrece «3 y 6 MSI, 9 al 18 %, 12 al 24 %». Eso es una tabla por banda, y
 * {@code rate_cards} ya sabía resolver bandas — lo que faltaba era distinguir el <b>propósito</b>,
 * porque la tasa de diferir no es la de originar el mismo producto.
 */
class TasaDeDiferimientoTest {

    private static final UUID PRODUCTO = UUID.randomUUID();
    private static final BigDecimal MORA = new BigDecimal("0.5400");

    private static RateCard diferir(int minPlazo, int maxPlazo, String tasa) {
        return RateCard.create(PRODUCTO, null, null, null, minPlazo, maxPlazo,
                new BigDecimal(tasa), MORA, RateCard.DIFERIMIENTO);
    }

    /** «3 y 6 MSI, 9 al 18 %, 12 al 24 %», tal cual lo ofrecería un producto de tarjeta. */
    private static final List<RateCard> TARJETA = List.of(
            diferir(3, 6, "0.0000"),
            diferir(7, 9, "0.1800"),
            diferir(10, 12, "0.2400"));

    private static RateCard resolver(int plazo) {
        return TARJETA.stream()
                .filter(rc -> rc.matches(null, null, plazo, RateCard.DIFERIMIENTO))
                .max(Comparator.comparingInt(RateCard::specificity))
                .orElseThrow();
    }

    @Test
    @DisplayName("la tabla ACEPTA tasa cero — un MSI ya se puede configurar")
    void aceptaTasaCero() {
        // El bloqueador era literal: `CHECK (nominal_rate > 0)`. Meses sin intereses es tasa cero,
        // así que un MSI se rechazaba antes de llegar a ninguna regla de negocio.
        RateCard msi = diferir(3, 6, "0.0000");

        assertThat(msi.getNominalRate()).isEqualByComparingTo("0");
        assertThat(msi.esMesesSinIntereses()).isTrue();
    }

    @Test
    @DisplayName("y sigue RECHAZANDO tasa moratoria cero")
    void rechazaMoratoriaCero() {
        // Una promoción puede no cobrar interés ordinario. Si además no cobrara moratorio, diferir
        // sería una forma de dejar de pagar sin consecuencia.
        assertThatThrownBy(() -> RateCard.create(PRODUCTO, null, null, null, 3, 6,
                BigDecimal.ZERO, BigDecimal.ZERO, RateCard.DIFERIMIENTO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("moratoria");
    }

    @Test
    @DisplayName("la tasa se resuelve por BANDA de plazo: 3-6 → 0 %, 7-9 → 18 %, 10-12 → 24 %")
    void resuelvePorBanda() {
        assertThat(resolver(3).getNominalRate()).isEqualByComparingTo("0.0000");
        assertThat(resolver(6).getNominalRate()).isEqualByComparingTo("0.0000");
        assertThat(resolver(9).getNominalRate()).isEqualByComparingTo("0.1800");
        assertThat(resolver(12).getNominalRate()).isEqualByComparingTo("0.2400");
    }

    @Test
    @DisplayName("una tasa de ORIGINACIÓN nunca gana la resolución de un diferimiento")
    void laDeOriginacionNoSeCuela() {
        // Sin el filtro por propósito, un producto no podría colocarse al 36 % y a la vez ofrecer
        // meses sin intereses: las dos tasas competirían por la misma banda de plazo. Y perder esa
        // competencia significa diferir al 36 % una compra que el cliente creía a MSI.
        RateCard originacion = RateCard.create(PRODUCTO, "BAJO", null, null, 1, 24,
                new BigDecimal("0.3600"), MORA);

        assertThat(originacion.matches("BAJO", null, 6, RateCard.DIFERIMIENTO)).isFalse();
        assertThat(originacion.matches("BAJO", null, 6, RateCard.ORIGINACION)).isTrue();
    }

    @Test
    @DisplayName("las filas anteriores a BK-25b son de originación, no de diferimiento")
    void lasViejasSonDeOriginacion() {
        // El default de la columna es ORIGINATION porque es lo que TODAS las filas existentes son.
        // Marcarlas de otro modo cambiaría la tasa con la que se coloca cada producto vivo.
        RateCard vieja = RateCard.create(PRODUCTO, null, null, null, 1, 12,
                new BigDecimal("0.2400"), MORA);

        assertThat(vieja.getPurpose()).isEqualTo(RateCard.ORIGINACION);
        assertThat(vieja.esMesesSinIntereses()).isFalse();
    }
}
