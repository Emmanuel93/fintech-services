package com.fintech.statement.application.service.assembly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** Pesos y fechas como se leen en México, sin depender del locale de la JVM. */
class FormatsTest {

    @Test
    @DisplayName("el periodo se escribe como se dice")
    void periodo() {
        assertThat(Formats.period(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15)))
                .isEqualTo("1 al 15 de noviembre de 2026");
        assertThat(Formats.period(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 11, 15)))
                .isEqualTo("16 de octubre al 15 de noviembre de 2026");
        assertThat(Formats.period(LocalDate.of(2026, 12, 16), LocalDate.of(2027, 1, 15)))
                .isEqualTo("16 de diciembre de 2026 al 15 de enero de 2027");
    }

    @Test
    @DisplayName("pesos con separador de miles y dos decimales; nulo es cero")
    void pesos() {
        assertThat(Formats.money(new BigDecimal("16666.665"))).isEqualTo("$16,666.67");
        assertThat(Formats.money(null)).isEqualTo("$0.00");
    }

    @Test
    @DisplayName("la tasa guardada como fracción se lee como porcentaje anual")
    void tasa() {
        assertThat(Formats.rate(new BigDecimal("0.48"))).isEqualTo("48.00 % anual");
        assertThat(Formats.rate(new BigDecimal("72"))).as("ya en porcentaje").isEqualTo("72.00 % anual");
        assertThat(Formats.rate(null)).isEqualTo("—");
    }

    @Test
    @DisplayName("un instante se fecha en hora de México: las 19:00 del 30 son del 30")
    void fechaDeNegocio() {
        ZoneId mx = ZoneId.of("America/Mexico_City");
        assertThat(Formats.date(Instant.parse("2026-12-01T01:00:00Z"), mx)).isEqualTo("30 nov 2026");
    }
}
