package com.fintech.origination.infrastructure.adapter.in.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El filtro «del día» de las bandejas es el día de México, no el de Greenwich.
 */
class ApplicationQuerySupportTest {

    private static final LocalDate DIA = LocalDate.of(2026, 10, 30);

    @Test
    @DisplayName("el día empieza a medianoche de México (06:00 UTC)")
    void elDiaEmpiezaAMedianocheDeMexico() {
        assertThat(ApplicationQuerySupport.startOfDay(DIA)).isEqualTo(Instant.parse("2026-10-30T06:00:00Z"));
        assertThat(ApplicationQuerySupport.startOfNextDay(DIA)).isEqualTo(Instant.parse("2026-10-31T06:00:00Z"));
    }

    @Test
    @DisplayName("una solicitud de las 19:50 en México cae en su día, no en el siguiente")
    void laSolicitudDeLaTardeCaeEnSuDia() {
        Instant tarde = Instant.parse("2026-10-31T01:50:00Z"); // 19:50 del 30 en México

        assertThat(tarde).isAfterOrEqualTo(ApplicationQuerySupport.startOfDay(DIA))
                .isBefore(ApplicationQuerySupport.startOfNextDay(DIA));
    }

    @Test
    @DisplayName("sin fecha no hay límite")
    void sinFechaNoHayLimite() {
        assertThat(ApplicationQuerySupport.startOfDay(null)).isNull();
        assertThat(ApplicationQuerySupport.startOfNextDay(null)).isNull();
    }

    @Test
    @DisplayName("rendimiento: 100 mil límites en menos de un segundo")
    void calcularLimitesEsBarato() {
        long inicio = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            ApplicationQuerySupport.startOfNextDay(DIA.plusDays(i % 365));
        }
        assertThat((System.nanoTime() - inicio) / 1_000_000).isLessThan(1_000L);
    }
}
