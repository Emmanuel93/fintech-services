package com.fintech.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** La fecha de negocio sale de la zona de la operación, no de la del contenedor. */
class BusinessClockTest {

    private static final ZoneId CDMX = ZoneId.of("America/Mexico_City");

    @Test
    void a_las_00_01_de_utc_en_mexico_todavia_es_ayer() {
        // El defecto que esto corrige: InstallmentDueJob corría a las 00:01 de UTC con
        // LocalDate.now() y daba por vencidas las cuotas del día siguiente en México.
        Instant medianocheUtc = Instant.parse("2026-10-31T00:01:00Z");
        BusinessClock reloj = new BusinessClock(Clock.fixed(medianocheUtc, CDMX));

        assertThat(reloj.today()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(LocalDate.ofInstant(medianocheUtc, ZoneId.of("UTC"))).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void sin_horario_de_verano_la_zona_queda_seis_horas_detras_de_utc_todo_el_ano() {
        for (String dia : new String[]{"2026-01-15T12:00:00Z", "2026-07-15T12:00:00Z"}) {
            assertThat(CDMX.getRules().getOffset(Instant.parse(dia)).getTotalSeconds()).isEqualTo(-6 * 3600);
        }
    }

    @Test
    void la_autoconfiguracion_usa_la_zona_declarada_y_por_defecto_la_de_mexico() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BusinessTimeAutoConfiguration.class));

        runner.run(ctx -> assertThat(ctx.getBean(BusinessClock.class).zone()).isEqualTo(CDMX));
        runner.withPropertyValues("fintech.business.zone=America/Monterrey")
                .run(ctx -> assertThat(ctx.getBean(BusinessClock.class).zone())
                        .isEqualTo(ZoneId.of("America/Monterrey")));
    }

    @Test
    void calcular_la_fecha_de_negocio_no_cuesta_nada() {
        BusinessClock reloj = BusinessClock.of(CDMX);
        long inicio = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            reloj.today();
        }
        // Rendimiento: lo llaman los jobs por cada cuenta que recorren.
        assertThat((System.nanoTime() - inicio) / 1_000_000).isLessThan(500);
    }
}
