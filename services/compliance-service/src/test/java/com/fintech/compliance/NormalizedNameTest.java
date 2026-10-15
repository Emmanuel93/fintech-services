package com.fintech.compliance;

import com.fintech.compliance.domain.NormalizedName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La normalización de nombres — la pieza de la que depende que el cruce encuentre o no encuentre.
 *
 * <p>Cada prueba fija un caso que aparece de verdad en listas mexicanas. Ninguna es teórica: si la
 * normalización se afloja, lo que se pierde es una coincidencia con alguien que sí estaba en la
 * lista, y eso no falla en ningún sitio — sale como «limpio».
 */
class NormalizedNameTest {

    @Test
    @DisplayName("los acentos no separan a una persona de sí misma")
    void accentsDoNotSplitAPerson() {
        // Las listas mezclan las dos formas, a veces en el mismo archivo.
        assertThat(NormalizedName.of("JOSÉ PÉREZ GARCÍA").value())
                .isEqualTo(NormalizedName.of("JOSE PEREZ GARCIA").value());
    }

    @Test
    @DisplayName("la Ñ se conserva como N, que es como la escriben los sistemas que no la manejan")
    void enieBecomesN() {
        assertThat(NormalizedName.of("MUÑOZ PEÑA").value()).isEqualTo("MUNOZ PENA");
    }

    @Test
    @DisplayName("la puntuación separa, no pega")
    void punctuationSeparates() {
        // «OBRIEN» sería un token que nadie escribe en ninguna lista.
        assertThat(NormalizedName.of("O'BRIEN").value()).isEqualTo("O BRIEN");
        assertThat(NormalizedName.of("GARCIA, JR.").value()).isEqualTo("GARCIA JR");
    }

    @Test
    @DisplayName("las partículas se conservan: «DE LA CRUZ» no es «CRUZ»")
    void particlesAreKept() {
        // Quitarlas multiplicaría los falsos positivos: «CRUZ» coincide con muchísima más gente.
        assertThat(NormalizedName.of("MARIA DE LA CRUZ").tokens())
                .containsExactly("MARIA", "DE", "LA", "CRUZ");
    }

    @Test
    @DisplayName("los títulos se descartan porque no identifican a nadie")
    void titlesAreNoise() {
        assertThat(NormalizedName.of("LIC. JUAN PEREZ").value()).isEqualTo("JUAN PEREZ");
        assertThat(NormalizedName.of("C. ANA LOPEZ").value()).isEqualTo("ANA LOPEZ");
    }

    @Test
    @DisplayName("el mismo nombre en otro orden es el mismo nombre")
    void orderDoesNotMatter() {
        // Unas listas traen «APELLIDO APELLIDO NOMBRE» y otras al revés. Comparar la cadena tal cual
        // falla con la misma persona escrita del otro modo.
        NormalizedName a = NormalizedName.of("PÉREZ GARCÍA JUAN");
        NormalizedName b = NormalizedName.of("Juan Perez Garcia");

        assertThat(a.value()).isNotEqualTo(b.value());     // la cadena directa NO coincide
        assertThat(a.sameTokensAs(b)).isTrue();            // y aun así es la misma persona
    }

    @Test
    @DisplayName("espacios de sobra, tabuladores y saltos no cambian nada")
    void whitespaceIsIrrelevant() {
        assertThat(NormalizedName.of("  JUAN   \t PEREZ  ").value()).isEqualTo("JUAN PEREZ");
    }

    @Test
    @DisplayName("un nombre que sólo es puntuación queda vacío, y eso es un rechazo")
    void punctuationOnlyIsEmpty() {
        // Las listas traen «---», «N/A», «.» como relleno. Cargarlos como entradas haría que
        // cualquier nombre vacío coincidiera con ellos.
        assertThat(NormalizedName.of("---").isEmpty()).isTrue();
        assertThat(NormalizedName.of("   ").isEmpty()).isTrue();
        assertThat(NormalizedName.of(null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("un nombre vacío NO coincide con otro vacío")
    void emptyNeverMatches() {
        // Si coincidiera, dos filas basura se cruzarían entre sí y con cualquier consulta vacía.
        assertThat(NormalizedName.of("---").sameTokensAs(NormalizedName.of("N/A"))).isFalse();
    }
}
