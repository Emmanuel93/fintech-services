package com.fintech.origination.infrastructure.adapter.out.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** La firma simulada sabe decir que no, y no arranca donde no debe. */
class SimulatedSignatureValidatorTest {

    private final SimulatedSignatureValidator validator = new SimulatedSignatureValidator();

    @Test
    void una_prueba_de_firma_normal_es_valida() {
        assertThat(validator.isValid("OTP", "123456")).isTrue();
        assertThat(validator.isValid("OTP", "OTP-482913")).isTrue();
    }

    @Test
    void vacia_o_el_escenario_de_rechazo_son_invalidas() {
        assertThat(validator.isValid("OTP", "")).isFalse();
        assertThat(validator.isValid("OTP", null)).isFalse();
        assertThat(validator.isValid("OTP", "000000")).isFalse();
        assertThat(validator.isValid("OTP", "OTP-000000")).isFalse();
    }

    @Test
    void no_arranca_en_un_ambiente_que_no_se_declara_bajo() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("docker");
        assertThatThrownBy(() -> validator.refuseToRunInProduction(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("firma electrónica simulada");
    }

    @Test
    void validar_es_inmediato() {
        long inicio = System.nanoTime();
        for (int i = 0; i < 100_000; i++) validator.isValid("OTP", "1234" + (i % 100));
        // Rendimiento: el simulador no agrega latencia a la firma.
        assertThat((System.nanoTime() - inicio) / 1_000_000).isLessThan(2_000L);
    }
}
