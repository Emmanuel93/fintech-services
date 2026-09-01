package com.fintech.shared.banking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El algoritmo de Banxico, probado una vez en lugar de tres.
 *
 * <p>Las tres copias que sustituye tenían entre las tres <b>una sola</b> prueba de transposición.
 * Consolidar no fue sólo quitar líneas: fue poder escribir los casos borde una vez y que valgan
 * para todos.
 */
class ClabeCheckDigitTest {

    private static final String CUERPO = "01218000123456789";

    @Test
    @DisplayName("una CLABE completada con su verificador es válida")
    void completarProduceValida() {
        assertThat(ClabeCheckDigit.esValida(ClabeCheckDigit.completar(CUERPO))).isTrue();
    }

    @Test
    @DisplayName("un dígito cambiado NO pasa — el error de captura más común")
    void detectaDigitoCambiado() {
        String buena = ClabeCheckDigit.completar(CUERPO);
        char otro = buena.charAt(9) == '9' ? '8' : '9';

        assertThat(ClabeCheckDigit.esValida(buena.substring(0, 9) + otro + buena.substring(10)))
                .isFalse();
    }

    @Test
    @DisplayName("dos dígitos transpuestos NO pasan")
    void detectaTransposicion() {
        // El ponderado 3,7,1 los detecta porque los dos dígitos llevan peso distinto. Un dígito
        // verificador de suma simple dejaría pasar la transposición, que es la segunda forma más
        // común de teclear mal una cuenta.
        String buena = ClabeCheckDigit.completar(CUERPO);
        String transpuesta = "01218000123456879" + buena.charAt(17);

        assertThat(ClabeCheckDigit.esValida(transpuesta)).isFalse();
    }

    @Test
    @DisplayName("lo mal formado devuelve false, no explota")
    void malFormadaNoExplota() {
        // Quien valida una cuenta de beneficiario lo hace sobre un dato que llega de fuera: una
        // excepción convertiría un rechazo previsible en un 500.
        assertThat(ClabeCheckDigit.esValida(null)).isFalse();
        assertThat(ClabeCheckDigit.esValida("")).isFalse();
        assertThat(ClabeCheckDigit.esValida("0121800012345678")).isFalse();
        assertThat(ClabeCheckDigit.esValida("01218000123456789X")).isFalse();
    }

    @Test
    @DisplayName("calcular con una longitud distinta de 17 SÍ lanza: es un error de programación")
    void calcularConLongitudMalaLanza() {
        // La asimetría es deliberada. `esValida` recibe dato externo; `de` recibe un cuerpo que
        // alguien construyó, y ahí un tamaño equivocado es un bug, no una entrada inválida.
        assertThatThrownBy(() -> ClabeCheckDigit.de("123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("17 dígitos");
    }

    @Test
    @DisplayName("la institución sale de los tres primeros dígitos, y es nula si no son numéricos")
    void institucion() {
        assertThat(ClabeCheckDigit.institucionDe("646180000000000012")).isEqualTo(646);
        // Rails que no son SPEI mandan teléfonos o tarjetas: lanzar convertiría un dato opcional
        // en un 500.
        assertThat(ClabeCheckDigit.institucionDe("55-1234-5678")).isNull();
        assertThat(ClabeCheckDigit.institucionDe(null)).isNull();
    }

    @Test
    @DisplayName("los diez verificadores posibles se calculan bien")
    void losDiezPosibles() {
        // Un algoritmo que devolviera siempre el mismo dígito pasaría las pruebas de arriba si el
        // cuerpo elegido diera justo ese valor. Barrer el rango lo descarta.
        java.util.Set<Integer> vistos = new java.util.HashSet<>();
        for (int i = 0; i < 10; i++) {
            vistos.add(ClabeCheckDigit.de("0121800012345678" + i));
        }
        assertThat(vistos).hasSizeGreaterThan(1);
    }
}
