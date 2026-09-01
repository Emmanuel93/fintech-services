package com.fintech.banking.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClabeTest {

    /** CLABE válida de BBVA construida con el dígito verificador que exige el algoritmo. */
    private static String valida() {
        String base = "01218000123456789";           // 17 dígitos
        return base + Clabe.digitoVerificador(base);
    }

    @Test
    @DisplayName("una CLABE bien formada se acepta y expone su institución")
    void aceptaLaValida() {
        Clabe c = Clabe.of(valida());
        assertThat(c.valor()).hasSize(18);
        assertThat(c.institucion()).isEqualTo("012");
    }

    @Test
    @DisplayName("un dígito cambiado NO pasa — es el error de captura más común")
    void rechazaDigitoCambiado() {
        String buena = valida();
        // Se altera el segundo dígito del número de cuenta, no el verificador: el caso real es
        // teclear mal el cuerpo y que el verificador delate la inconsistencia.
        char alterado = buena.charAt(9) == '9' ? '8' : '9';
        String mala = buena.substring(0, 9) + alterado + buena.substring(10);

        assertThatThrownBy(() -> Clabe.of(mala))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dígito verificador");
    }

    @Test
    @DisplayName("dos dígitos transpuestos NO pasan")
    void rechazaTransposicion() {
        // El ponderado 3,7,1 detecta la transposición porque los dos dígitos llevan peso distinto.
        String base = "01218000123456789";
        String buena = base + Clabe.digitoVerificador(base);
        String transpuesta = "01218000123456879" + buena.charAt(17);

        assertThatThrownBy(() -> Clabe.of(transpuesta))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("longitud distinta de 18 o con letras se rechaza antes de calcular nada")
    void rechazaMalFormada() {
        assertThatThrownBy(() -> Clabe.of("0121800012345678"))
                .hasMessageContaining("18 dígitos");
        assertThatThrownBy(() -> Clabe.of("01218000123456789X"))
                .hasMessageContaining("18 dígitos");
        assertThatThrownBy(() -> Clabe.of(null))
                .hasMessageContaining("requerida");
    }

    @Test
    @DisplayName("espacios y guiones de la captura no invalidan la CLABE")
    void toleraSeparadores() {
        String buena = valida();
        String conFormato = buena.substring(0, 3) + " " + buena.substring(3, 6) + "-"
                + buena.substring(6);
        assertThat(Clabe.of(conFormato).valor()).isEqualTo(buena);
    }

    @Test
    @DisplayName("el mensaje de error NO revela el dígito correcto")
    void noFiltraElVerificador() {
        // Si el mensaje dijera «debía ser 7», quien captura ajustaría el último dígito hasta que la
        // validación callara — y la CLABE seguiría siendo la de otra cuenta.
        String base = "01218000123456789";
        int correcto = Clabe.digitoVerificador(base);
        String mala = base + ((correcto + 1) % 10);

        assertThatThrownBy(() -> Clabe.of(mala))
                .hasMessageNotContaining("debía ser")
                .hasMessageNotContaining("esperado " + correcto);
    }

    @Test
    @DisplayName("la representación textual nunca lleva la CLABE completa")
    void nuncaSeImprimeEntera() {
        Clabe c = Clabe.of(valida());
        assertThat(c.toString()).doesNotContain(c.valor()).startsWith("****");
    }
}
