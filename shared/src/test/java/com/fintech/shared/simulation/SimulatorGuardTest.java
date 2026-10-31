package com.fintech.shared.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Qué ambientes dejan arrancar un simulador, y por qué {@code docker} ya no es uno de ellos. */
class SimulatorGuardTest {

    @Test
    void un_ambiente_bajo_declarado_permite_el_simulador() {
        assertThat(SimulatorGuard.isAllowed("local", new String[]{"docker"})).isTrue();
        assertThat(SimulatorGuard.isAllowed("QA", new String[]{})).isTrue();
    }

    @Test
    void el_perfil_docker_a_secas_ya_no_basta() {
        // Es lo que hacía pasar al compose promovido a producción sin cambiar nada.
        assertThat(SimulatorGuard.isAllowed("", new String[]{"docker"})).isFalse();
    }

    @Test
    void sin_declaracion_ni_perfiles_no_arranca() {
        assertThat(SimulatorGuard.isAllowed(null, new String[]{})).isFalse();
        assertThat(SimulatorGuard.isAllowed("", new String[]{})).isFalse();
    }

    @Test
    void las_pruebas_corren_con_el_perfil_test() {
        assertThat(SimulatorGuard.isAllowed("", new String[]{"test"})).isTrue();
    }

    @Test
    void una_declaracion_de_produccion_gana_aunque_conviva_con_un_perfil_bajo() {
        assertThat(SimulatorGuard.isAllowed("production", new String[]{"test"})).isFalse();
        assertThat(SimulatorGuard.isAllowed("local", new String[]{"prod"})).isFalse();
    }

    @Test
    void un_ambiente_desconocido_es_produccion_hasta_que_se_declare() {
        assertThat(SimulatorGuard.isAllowed("demo-clientes", new String[]{"docker"})).isFalse();
    }

    @Test
    void el_error_dice_que_simulador_es_y_como_encender_el_real() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("docker");
        assertThatThrownBy(() -> SimulatorGuard.requireNonProduction(env, "MockX (proveedor X)", "X_MODE=real"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MockX (proveedor X)")
                .hasMessageContaining("X_MODE=real")
                .hasMessageContaining("FINTECH_ENVIRONMENT");
    }

    @Test
    void con_ambiente_declarado_arranca() {
        MockEnvironment env = new MockEnvironment().withProperty("fintech.environment", "local");
        env.setActiveProfiles("docker");
        assertThatCode(() -> SimulatorGuard.requireNonProduction(env, "MockX", "X_MODE=real"))
                .doesNotThrowAnyException();
    }
}
