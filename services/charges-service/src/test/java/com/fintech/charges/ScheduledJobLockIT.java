package com.fintech.charges;

import com.fintech.charges.application.service.InterestAccrualService;
import com.fintech.charges.infrastructure.job.DailyAccrualJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Dos réplicas disparan el mismo cron al mismo tiempo: sólo una lo corre.
 *
 * <p>Antes cada pod tenía su {@code @Scheduled} y nada lo coordinaba; aquí la clave natural del
 * devengo frenaba el cargo duplicado, pero el trabajo se hacía dos veces —y en billing y risk ni
 * eso—. El candado vive en {@code charges.shedlock} y lo decide el reloj de PostgreSQL.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
// Mismo motivo que en AccrualIdempotencyIT: sin broker embebido la prueba depende del stack local.
@EmbeddedKafka(partitions = 1, topics = {"charges.charge-applied", "charges.charge-reversed"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class ScheduledJobLockIT {

    @Autowired DailyAccrualJob dailyAccrualJob;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean InterestAccrualService accrualService;

    @BeforeEach
    void sinCandados() {
        jdbc.update("DELETE FROM charges.shedlock");
    }

    @Test
    @DisplayName("la tabla del candado vive en el esquema del servicio")
    void laTablaViveEnElEsquemaDelServicio() {
        Integer tablas = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'charges' AND table_name = 'shedlock'""", Integer.class);
        assertThat(tablas).isEqualTo(1);
    }

    @Test
    @DisplayName("dos réplicas a la vez: una corre el devengo, la otra lo salta sin esperar")
    void dosReplicasUnaSolaCorrida() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Long>> corridas = new ArrayList<>();
        for (int replica = 0; replica < 2; replica++) {
            corridas.add(pool.submit(() -> {
                salida.await();
                long inicio = System.nanoTime();
                dailyAccrualJob.runDailyAccrual();
                return (System.nanoTime() - inicio) / 1_000_000;
            }));
        }
        salida.countDown();
        List<Long> duraciones = new ArrayList<>();
        for (Future<Long> f : corridas) duraciones.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();

        verify(accrualService, times(1)).findScheduleIdsForAccrual(any());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM charges.shedlock WHERE name = 'charges.daily-accrual'", Integer.class))
                .isEqualTo(1);
        // Rendimiento: la réplica que no toma el candado regresa de inmediato; no se forma en fila.
        assertThat(duraciones.stream().mapToLong(Long::longValue).min().orElseThrow()).isLessThan(2_000L);
    }
}
