package com.fintech.creditportfolio.bnpl;

import com.fintech.creditportfolio.domain.config.OpcionesDePago;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BNPL es una decisión del alta, no una propiedad del producto.
 *
 * <p>Lo que el producto trae es el <b>tope</b> — el campo se llama {@code bnplMaxDeferralDays}—.
 * El código lo usaba como la cifra a aplicar y sin mirar si alguien lo había pedido:
 *
 * <pre>{@code return LocalDate.now().plusDays(opciones.bnplMaxDeferralDays());}</pre>
 *
 * <p>Como {@code PL-IND-STD-V1} tiene BNPL habilitado, <b>ningún préstamo personal empezaba a pagar
 * cuando debía</b>: el plan entero nacía corrido el máximo, para todos, sin que nadie lo pidiera y
 * sin que nada lo dijera. Salió sembrando S5 y midiendo la fecha de la primera cuota.
 */
class BnplEsUnaSolicitudNoUnaPropiedadTest {

    private static final LocalDate HOY = LocalDate.of(2026, 8, 27);

    /** Como el préstamo personal sembrado: BNPL habilitado con tope de 30 días. */
    private static OpcionesDePago conBnpl(int tope) {
        return new OpcionesDePago("NONE", "NONE", 3, 1, 24,
                true, tope, true, 1, "GIFT", true, List.of());
    }

    @Test
    @DisplayName("Sin solicitud NO hay BNPL — es la regresión que corría el plan de toda la cartera")
    void sin_solicitud_no_hay_bnpl() {
        assertThat(conBnpl(30).arranqueDeBnpl(HOY, null)).isEqualTo(HOY);
        assertThat(conBnpl(30).arranqueDeBnpl(HOY, 0)).isEqualTo(HOY);
    }

    @Test
    @DisplayName("Lo pedido se respeta cuando cabe en el tope")
    void lo_pedido_se_respeta() {
        assertThat(conBnpl(30).arranqueDeBnpl(HOY, 15)).isEqualTo(HOY.plusDays(15));
        assertThat(conBnpl(30).arranqueDeBnpl(HOY, 30)).isEqualTo(HOY.plusDays(30));
    }

    @Test
    @DisplayName("Pedir de más se recorta al tope, no tumba el alta")
    void pedir_de_mas_se_recorta() {
        // El producto ya declaró hasta dónde espera. Negar el alta entera por pedir de más
        // convierte un límite en un obstáculo.
        assertThat(conBnpl(30).arranqueDeBnpl(HOY, 90)).isEqualTo(HOY.plusDays(30));
    }

    @Test
    @DisplayName("Un producto sin BNPL ignora la solicitud en vez de aplicarla")
    void producto_sin_bnpl_ignora_la_solicitud() {
        OpcionesDePago sinBnpl = new OpcionesDePago("NONE", "NONE", 3, 1, 24,
                false, 30, true, 1, "GIFT", true, List.of());
        assertThat(sinBnpl.arranqueDeBnpl(HOY, 15)).isEqualTo(HOY);
    }

    @Test
    @DisplayName("Un tope en cero o nulo no es BNPL de cero días: es un producto que no lo ofrece")
    void tope_en_cero_no_ofrece_bnpl() {
        assertThat(conBnpl(0).arranqueDeBnpl(HOY, 15)).isEqualTo(HOY);
        OpcionesDePago topeNulo = new OpcionesDePago("NONE", "NONE", 3, 1, 24,
                true, null, true, 1, "GIFT", true, List.of());
        assertThat(topeNulo.arranqueDeBnpl(HOY, 15)).isEqualTo(HOY);
    }
}
