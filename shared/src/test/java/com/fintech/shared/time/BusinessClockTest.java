package com.fintech.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    void shifting_the_clock_moves_today_keeps_the_time_running_and_reverts() {
        BusinessClock reloj = new BusinessClock(Clock.fixed(Instant.parse("2026-11-01T18:30:00Z"), CDMX));
        assertThat(reloj.today()).isEqualTo(LocalDate.of(2026, 11, 1));

        reloj.shiftTo(LocalDate.of(2026, 12, 15));

        // Same time of day, 44 days later: jobs see a shifted "today" with the same time.
        assertThat(reloj.today()).isEqualTo(LocalDate.of(2026, 12, 15));
        assertThat(reloj.now()).isEqualTo(Instant.parse("2026-12-15T18:30:00Z"));
        assertThat(reloj.shiftedDays()).isEqualTo(44);

        reloj.shiftTo(LocalDate.of(2026, 10, 20));
        assertThat(reloj.shiftedDays()).isEqualTo(-12);

        reloj.resetShift();
        assertThat(reloj.today()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(reloj.shiftedDays()).isZero();
    }

    @Test
    void the_shiftable_clock_only_exists_with_test_support_enabled() {
        new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BusinessTimeAutoConfiguration.class))
                .withPropertyValues("fintech.environment=local")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(TestSupportClockController.class));
        new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BusinessTimeAutoConfiguration.class))
                .withPropertyValues("fintech.environment=local", "fintech.test-support.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(TestSupportClockController.class));
        // It does not start in production even with the flag on: the guard blocks it.
        new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BusinessTimeAutoConfiguration.class))
                .withPropertyValues("fintech.environment=prod", "fintech.test-support.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
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

    @Test
    void shifting_to_a_moment_sets_the_time_of_day_and_the_clock_keeps_running_from_it() {
        // Real time 2026-11-03 20:00 in Mexico City (the hour a run happened to be launched).
        Instant real = Instant.parse("2026-11-04T02:00:00Z");
        BusinessClock reloj = new BusinessClock(Clock.fixed(real, CDMX));

        reloj.shiftTo(LocalDateTime.of(2026, 10, 30, 18, 30));

        assertThat(reloj.today()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(LocalDateTime.now(reloj.clock())).isEqualTo(LocalDateTime.of(2026, 10, 30, 18, 30));
        // Same moment whatever the hour the run was launched: the result no longer depends on it.
        BusinessClock otro = new BusinessClock(Clock.fixed(Instant.parse("2026-11-03T15:00:00Z"), CDMX));
        otro.shiftTo(LocalDateTime.of(2026, 10, 30, 18, 30));
        assertThat(otro.now()).isEqualTo(reloj.now());

        reloj.resetShift();
        assertThat(reloj.now()).isEqualTo(real);
    }

    @Test
    void the_clock_view_follows_every_shift_a_snapshot_does_not() {
        BusinessClock reloj = new BusinessClock(Clock.fixed(Instant.parse("2026-11-04T02:00:00Z"), CDMX));
        Clock view = reloj.asClock();
        Clock snapshot = reloj.clock();

        reloj.shiftTo(LocalDateTime.of(2026, 3, 2, 10, 0));

        assertThat(LocalDate.now(view)).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(LocalDate.now(snapshot)).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(view.getZone()).isEqualTo(CDMX);
        assertThat(view.withZone(ZoneId.of("UTC")).instant()).isEqualTo(reloj.now());
        assertThat(view.millis()).isEqualTo(reloj.now().toEpochMilli());
    }

    @Test
    void the_test_support_endpoint_takes_a_moment_or_a_date_but_not_both() throws Exception {
        BusinessClock reloj = new BusinessClock(Clock.fixed(Instant.parse("2026-11-04T02:00:00Z"), CDMX));
        MockEnvironment env = new MockEnvironment().withProperty("fintech.environment", "local");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestSupportClockController(reloj, env)).build();

        mvc.perform(post("/internal/test-support/clock").param("at", "2026-10-30T18:30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value("2026-10-30"))
                .andExpect(jsonPath("$.local").value("2026-10-30T18:30"));
        mvc.perform(post("/internal/test-support/clock").param("today", "2026-12-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value("2026-12-15"));
        mvc.perform(post("/internal/test-support/clock"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/test-support/clock").param("at", "2026-10-30T18:30").param("today", "2026-10-30"))
                .andExpect(status().isBadRequest());
    }
}
