package com.fintech.stp.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Casos reales del legado, más el que el legado no resolvía (B7). */
class BeneficiaryNameMatcherTest {

    @Test
    @DisplayName("Ignora acentos, comas, diagonales y espacios")
    void normalisesSeparatorsAndAccents() {
        assertThat(BeneficiaryNameMatcher.matches(
                "NIKTE-HA DE GUADALUPE OROPEZA VAZQUEZ",
                "NIKTE HA DE GUADALUPE,OROPEZA/VAZQUEZ")).isTrue();

        assertThat(BeneficiaryNameMatcher.matches(
                "JOSÉ MARÍA ÁVILA NÚÑEZ", "JOSE MARIA AVILA NUNEZ")).isTrue();

        assertThat(BeneficiaryNameMatcher.matches(
                "Dennis MacAlistair Ritchie ", " DENNIS MACALISTAIR RITCHIE ")).isTrue();
    }

    @Test
    @DisplayName("Nombres realmente distintos no coinciden")
    void differentNamesDoNotMatch() {
        assertThat(BeneficiaryNameMatcher.matches("JUAN PEREZ", "JUAN LOPEZ")).isFalse();
        assertThat(BeneficiaryNameMatcher.matches(null, "JUAN PEREZ")).isFalse();
    }

    @Test
    @DisplayName("B7 · un nombre truncado a 40 coincide con el completo del CEP")
    void tolerateTruncationTo40() {
        String completo = "MARIA DE LOS ANGELES HERNANDEZ RODRIGUEZ DE LA TORRE";
        String enviado = completo.substring(0, 40);

        // Cómo se comportaba el legado: todo nombre largo daba false.
        assertThat(BeneficiaryNameMatcher.matches(enviado, completo)).isFalse();

        // Con tolerancia al truncado, coincide.
        assertThat(BeneficiaryNameMatcher.matchesAllowingTruncation(enviado, completo, 40)).isTrue();

        // Y sigue detectando un beneficiario realmente distinto.
        assertThat(BeneficiaryNameMatcher.matchesAllowingTruncation(
                enviado, "OTRO NOMBRE COMPLETAMENTE DISTINTO AQUI XX", 40)).isFalse();
    }
}
