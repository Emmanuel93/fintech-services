package com.fintech.stp.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La clave de rastreo es el identificador de la orden ante Banxico.
 *
 * <p>El legado la derivaba del PK autoincremental de la tabla y de un prefijo constante de dos
 * letras: imposible de usar con más de una empresa.
 */
class TrackingKeyTest {

    @Test
    @DisplayName("Formato prefijo + yyyyMMdd + 14 dígitos")
    void format() {
        assertThat(TrackingKey.generate("SV", LocalDate.of(2026, 8, 11), 123456789L).value())
                .isEqualTo("SV2026081100000123456789")
                .hasSize(24);
    }

    @Test
    @DisplayName("Cada empresa lleva su propio prefijo")
    void prefixIsPerCompany() {
        assertThat(TrackingKey.generate("AC", LocalDate.of(2026, 8, 11), 1L).value())
                .isEqualTo("AC2026081100000000000001");
    }

    @Test
    @DisplayName("Un prefijo ausente o demasiado largo falla en el acto")
    void invalidPrefix() {
        assertThatThrownBy(() -> TrackingKey.generate(null, LocalDate.now(), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrackingKey.generate("DEMASIADO", LocalDate.now(), 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
