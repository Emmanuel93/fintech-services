package com.fintech.creditportfolio.config;

import com.fintech.creditportfolio.domain.config.OpcionesDePago;
import com.fintech.creditportfolio.domain.config.OpcionesDePago.BandaDeDiferimiento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un producto no se configura campo a campo: los campos se condicionan entre sí.
 *
 * <p>Una combinación puede ser inválida sin que ninguno de sus valores lo sea, y eso no lo caza
 * nadie revisando el JSON a ojo. Las tres configuraciones sembradas que tenían contradicciones
 * llevaban meses ahí y no rompían nada — sólo mentían sobre lo que el producto hace, y el síntoma
 * aparecía lejos: «el producto PL-IND-STD-V1 no admite saltar pagos» cuando el catálogo decía que
 * sí, o un plan de diferimiento sin tasa con la que calcularlo.
 */
class ConfiguracionesQueSeContradicenTest {

    private static OpcionesDePago tarjetaSana() {
        return new OpcionesDePago("POST_HOC", "BEFORE_CUTOFF", 3, 3, 12,
                false, null, true, 1, "DEFERRAL", true,
                List.of(new BandaDeDiferimiento(3, 6, BigDecimal.ZERO),
                        new BandaDeDiferimiento(7, 9, new BigDecimal("0.1800")),
                        new BandaDeDiferimiento(10, 12, new BigDecimal("0.2400"))));
    }

    @Test
    @DisplayName("La tarjeta sembrada es coherente — la validación no inventa problemas")
    void la_tarjeta_sembrada_es_coherente() {
        assertThat(tarjetaSana().incoherencias()).isEmpty();
    }

    @Test
    @DisplayName("Diferir después sin ventana es una capacidad que no se puede ejercer nunca")
    void post_hoc_sin_ventana() {
        OpcionesDePago o = new OpcionesDePago("POST_HOC", "NONE", 3, 3, 12,
                false, null, false, 0, null, true,
                List.of(new BandaDeDiferimiento(3, 12, BigDecimal.ZERO)));

        assertThat(o.incoherencias()).anySatisfy(m -> assertThat(m).contains("deferralCutoffRule"));
    }

    @Test
    @DisplayName("Plazos declarados en un producto que no difiere: parámetros para algo que no hace")
    void plazos_en_un_producto_que_no_difiere() {
        // Es exactamente PL-IND-STD-V1 tal como estaba sembrado.
        OpcionesDePago prestamo = new OpcionesDePago("NONE", "NONE", 3, 1, 24,
                true, 30, true, 1, "GIFT", true, List.of());

        assertThat(prestamo.incoherencias())
                .anySatisfy(m -> assertThat(m).contains("plazos de diferimiento declarados"));
    }

    @Test
    @DisplayName("Un hueco entre bandas deja plazos que el producto admite y no sabe cobrar")
    void hueco_entre_bandas() {
        OpcionesDePago o = new OpcionesDePago("POST_HOC", "BEFORE_CUTOFF", 3, 3, 12,
                false, null, false, 0, null, true,
                List.of(new BandaDeDiferimiento(3, 6, BigDecimal.ZERO),
                        new BandaDeDiferimiento(10, 12, new BigDecimal("0.2400"))));

        assertThat(o.incoherencias()).anySatisfy(m -> assertThat(m).contains("7-9"));
    }

    @Test
    @DisplayName("Dos bandas solapadas son dos tasas para el mismo plazo")
    void bandas_solapadas() {
        OpcionesDePago o = new OpcionesDePago("POST_HOC", "BEFORE_CUTOFF", 3, 3, 12,
                false, null, false, 0, null, true,
                List.of(new BandaDeDiferimiento(3, 9, BigDecimal.ZERO),
                        new BandaDeDiferimiento(6, 12, new BigDecimal("0.2400"))));

        assertThat(o.incoherencias()).anySatisfy(m -> assertThat(m).contains("solapa"));
    }

    @Test
    @DisplayName("El plazo por omisión tiene que caer dentro del rango: lo recibe quien no elige")
    void el_plazo_por_omision_dentro_del_rango() {
        OpcionesDePago o = new OpcionesDePago("POST_HOC", "BEFORE_CUTOFF", 18, 3, 12,
                false, null, false, 0, null, true,
                List.of(new BandaDeDiferimiento(3, 12, BigDecimal.ZERO)));

        assertThat(o.incoherencias()).anySatisfy(m -> assertThat(m).contains("deferralDefaultTerm=18"));
    }

    @Test
    @DisplayName("Salto ofrecido con tope cero: declarado y no usable ni una vez")
    void salto_ofrecido_sin_tope() {
        OpcionesDePago o = new OpcionesDePago("NONE", "NONE", null, null, null,
                false, null, true, 0, "GIFT", true, List.of());

        assertThat(o.incoherencias()).anySatisfy(m -> assertThat(m).contains("maxSkipsPerCycle=0"));
    }

    @Test
    @DisplayName("BNPL de cero días es no ofrecer BNPL")
    void bnpl_de_cero_dias() {
        OpcionesDePago o = new OpcionesDePago("NONE", "NONE", null, null, null,
                true, 0, false, 0, null, true, List.of());

        assertThat(o.incoherencias()).anySatisfy(m -> assertThat(m).contains("BNPL de cero días"));
    }

    @Test
    @DisplayName("AT_DISPOSITION sin bandas es correcto: el plan nace con la colocación")
    void at_disposition_no_necesita_bandas() {
        // El distribuidor decide el plazo al colocar y el plan cobra la tasa del producto. Exigirle
        // tabla de diferimiento sería pedirle tarifa para algo que no ocurre.
        OpcionesDePago distribuidor = new OpcionesDePago("AT_DISPOSITION", "NONE", 12, 3, 24,
                false, null, false, 0, "DEFERRAL", true, List.of());

        assertThat(distribuidor.incoherencias()).isEmpty();
    }
}
