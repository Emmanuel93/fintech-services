package com.fintech.stp.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Dígito verificador de Banxico: evita un viaje a STP para una orden que va a rechazar. */
class ClabeValidatorTest {

    @Test
    @DisplayName("Una CLABE con verificador correcto es válida")
    void validClabe() {
        String clabe = ClabeValidator.withCheckDigit("64618037800000000");
        assertThat(clabe).hasSize(18);
        assertThat(ClabeValidator.isValid(clabe)).isTrue();
    }

    @Test
    @DisplayName("Alterar el verificador la invalida")
    void wrongCheckDigit() {
        String clabe = ClabeValidator.withCheckDigit("64618037800000000");
        char last = clabe.charAt(17);
        char wrong = last == '0' ? '1' : (char) (last - 1);
        assertThat(ClabeValidator.isValid(clabe.substring(0, 17) + wrong)).isFalse();
    }

    @Test
    @DisplayName("Longitud incorrecta, letras o null la invalidan")
    void malformedInput() {
        assertThat(ClabeValidator.isValid("6461803780000000")).isFalse();
        assertThat(ClabeValidator.isValid("64618037800000000X")).isFalse();
        assertThat(ClabeValidator.isValid(null)).isFalse();
    }
}
