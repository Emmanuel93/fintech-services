package com.fintech.disbursement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La ventana operativa es donde el legado se rompía: dos crons, cuatro réplicas y ningún estado
 * compartido. Aquí es una función pura y se prueba como tal.
 */
class OperatingWindowTest {

    private static final ZoneId MX = ZoneId.of("America/Mexico_City");

    /** SPEI: abre 17:10 y cierra 16:50 del día siguiente. La franja cerrada es el corte de Banxico. */
    private static final OperatingWindow SPEI = new OperatingWindow(
            LocalTime.of(17, 10), LocalTime.of(16, 50), MX, EnumSet.allOf(DayOfWeek.class));

    private static ZonedDateTime at(String isoLocal) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocal), MX);
    }

    @Test
    @DisplayName("ventana envolvente: abierta a las 09:00 y a las 22:00, cerrada a las 17:00")
    void ventana_envolvente() {
        assertThat(SPEI.isOpenAt(at("2026-08-11T09:00:00"))).isTrue();
        assertThat(SPEI.isOpenAt(at("2026-08-11T22:00:00"))).isTrue();
        assertThat(SPEI.isOpenAt(at("2026-08-11T17:00:00"))).isFalse();
    }

    @Test
    @DisplayName("los bordes cuentan: 17:10 abre, 16:50 cierra")
    void bordes() {
        assertThat(SPEI.isOpenAt(at("2026-08-11T17:10:00"))).isTrue();
        assertThat(SPEI.isOpenAt(at("2026-08-11T16:50:00"))).isFalse();
        assertThat(SPEI.isOpenAt(at("2026-08-11T16:49:59"))).isTrue();
    }

    @Test
    @DisplayName("la siguiente apertura del mismo día si aún no llegó")
    void siguiente_apertura() {
        assertThat(SPEI.nextOpening(at("2026-08-11T17:00:00")))
                .isEqualTo(at("2026-08-11T17:10:00"));
    }

    @Test
    @DisplayName("un rail sin corte (start == end) está siempre abierto")
    void veinticuatro_horas() {
        OperatingWindow always = new OperatingWindow(
                LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, MX, EnumSet.allOf(DayOfWeek.class));
        assertThat(always.isAlwaysOpen()).isTrue();
        assertThat(always.isOpenAt(at("2026-08-11T03:00:00"))).isTrue();
        assertThat(always.nextOpening(at("2026-08-11T03:00:00"))).isEqualTo(at("2026-08-11T03:00:00"));
    }

    @Test
    @DisplayName("un rail sólo hábil salta el fin de semana")
    void dias_habiles() {
        OperatingWindow habiles = new OperatingWindow(
                LocalTime.of(9, 0), LocalTime.of(17, 0), MX,
                Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));

        // 2026-08-15 es sábado.
        assertThat(habiles.isOpenAt(at("2026-08-15T10:00:00"))).isFalse();
        assertThat(habiles.nextOpening(at("2026-08-15T10:00:00")).getDayOfWeek())
                .isEqualTo(DayOfWeek.MONDAY);
    }
}
