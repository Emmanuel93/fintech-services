package com.fintech.disbursement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validar aquí evita un viaje completo al proveedor para obtener el mismo "no" (DB-04).
 */
class ClabeValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"646180157000000004", "012180015566800003", "002180700000000008"})
    void acepta_clabes_validas(String clabe) {
        assertThat(ClabeValidator.isValid(clabe)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("rechaza longitud, no dígitos, nulo y dígito verificador malo")
    @ValueSource(strings = {"6461801570000000", "64618015700000000X", "646180157000000005", ""})
    void rechaza_clabes_invalidas(String clabe) {
        assertThat(ClabeValidator.isValid(clabe)).isFalse();
    }

    @Test
    void rechaza_nulo() {
        assertThat(ClabeValidator.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("los tres primeros dígitos son la institución")
    void institucion() {
        assertThat(ClabeValidator.institutionOf("646180157000000004")).isEqualTo(646);
    }
}
