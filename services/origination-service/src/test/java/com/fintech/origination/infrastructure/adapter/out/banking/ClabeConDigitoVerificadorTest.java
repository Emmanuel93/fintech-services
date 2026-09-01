package com.fintech.origination.infrastructure.adapter.out.banking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La CLABE se valida donde entra, no donde se paga.
 *
 * <p>El stub anterior comprobaba dieciocho dígitos y nada más, así que aceptaba
 * {@code 002180114414524862} —una CLABE cuyo dígito verificador está mal— y la dejaba atravesar
 * scoring, oferta, contrato, firma y alta. El error salía a la luz en {@code disbursement}, al ir a
 * mandar el dinero, con el crédito ya otorgado, activo y devengando.
 *
 * <p>Las cadenas de estas pruebas son reales: salieron de la cartera sembrada, donde ocho de
 * diecinueve cuentas tenían el dígito equivocado y nadie lo sabía.
 */
class ClabeConDigitoVerificadorTest {

    private final ClabeConDigitoVerificador validador = new ClabeConDigitoVerificador();

    @Test
    @DisplayName("Una CLABE con su dígito verificador correcto se acepta")
    void acepta_la_valida() {
        assertThat(validador.isValid("032180000118359719")).isTrue();
    }

    @Test
    @DisplayName("Dieciocho dígitos con el verificador mal se RECHAZAN — es justo lo que el stub dejaba pasar")
    void rechaza_el_digito_verificador_equivocado() {
        // De la cartera sembrada. El verificador correcto de ese cuerpo es 3, no 2.
        assertThat(validador.isValid("002180114414524862")).isFalse();
        // Ídem: le corresponde 1, no 7.
        assertThat(validador.isValid("002180129432656937")).isFalse();
    }

    @Test
    @DisplayName("Un dígito transpuesto se cae, que es para lo que existe el verificador")
    void caza_la_transposicion() {
        String buena = "032180000118359719";
        String transpuesta = buena.substring(0, 8) + buena.charAt(9) + buena.charAt(8) + buena.substring(10);

        assertThat(transpuesta).isNotEqualTo(buena);
        assertThat(validador.isValid(transpuesta)).isFalse();
    }

    @Test
    @DisplayName("Lo mal formado se rechaza sin reventar: el dato viene de fuera")
    void lo_mal_formado_no_revienta() {
        assertThat(validador.isValid(null)).isFalse();
        assertThat(validador.isValid("")).isFalse();
        assertThat(validador.isValid("000")).isFalse();
        assertThat(validador.isValid("03218000011835971")).isFalse();   // 17
        assertThat(validador.isValid("0321800001183597199")).isFalse(); // 19
        assertThat(validador.isValid("03218000011835971X")).isFalse();
    }
}
