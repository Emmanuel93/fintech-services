package com.fintech.shared.lock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockKeyTest {

    @Test
    @DisplayName("compone la llave con el prefijo del monorepo")
    void componeLaLlave() {
        LockKey key = LockKey.of("closing", "run", "2026-08-24", "ACCRUAL", "ALL");
        assertThat(key.value()).isEqualTo("fintech:lock:closing:run:2026-08-24:ACCRUAL:ALL");
    }

    @Test
    @DisplayName("sin discriminador no deja los dos puntos colgando")
    void sinDiscriminador() {
        assertThat(LockKey.of("accounting", "period-close").value())
                .isEqualTo("fintech:lock:accounting:period-close");
    }

    @Test
    @DisplayName("omite los segmentos nulos o en blanco en vez de producir '::'")
    void omiteSegmentosVacios() {
        LockKey key = LockKey.of("closing", "unit", "run-1", null, "  ", "cuenta-9");
        assertThat(key.value()).isEqualTo("fintech:lock:closing:unit:run-1:cuenta-9");
    }

    @Test
    @DisplayName("dos llaves construidas igual son la misma llave")
    void mismaLlave() {
        assertThat(LockKey.of("closing", "unit", "a", "b"))
                .isEqualTo(LockKey.of("closing", "unit", "a", "b"));
    }

    @Test
    @DisplayName("el contador de fencing vive en otro espacio de nombres")
    void fenceEnOtroEspacio() {
        LockKey key = LockKey.of("closing", "run", "2026-08-24");
        // Si compartiera llave con el candado, el DEL de la liberación reiniciaría el contador
        // y dos titulares sucesivos recibirían el mismo número.
        assertThat(key.fenceKey()).isEqualTo("fintech:fence:closing:run:2026-08-24");
        assertThat(key.fenceKey()).isNotEqualTo(key.value());
    }

    @Test
    @DisplayName("rechaza dominio o propósito vacíos")
    void rechazaVacios() {
        assertThatThrownBy(() -> LockKey.of("", "run")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LockKey.of("closing", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
